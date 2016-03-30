/*
 * Copyright 2015 Distributed Ledger Group LLC.  Distributed as Licensed Company IP to DLG Group Members
 * pursuant to the August 7, 2015 Advisory Services Agreement and subject to the Company IP License terms
 * set forth therein.
 *
 * All other rights reserved.
 */

package protocols

import co.paralleluniverse.fibers.Suspendable
import com.google.common.util.concurrent.ListenableFuture
import contracts.Cash
import contracts.sumCashBy
import core.*
import core.crypto.DigitalSignature
import core.crypto.signWithECDSA
import core.messaging.LegallyIdentifiableNode
import core.messaging.SingleMessageRecipient
import core.messaging.StateMachineManager
import protocols.TimestampingProtocol
import core.protocols.ProtocolLogic
import core.utilities.ProgressTracker
import core.utilities.trace
import java.security.KeyPair
import java.security.PublicKey
import java.security.SignatureException
import java.time.Instant
import kotlin.system.exitProcess

/**
 * This asset trading protocol implements a "delivery vs payment" type swap. It has two parties (B and S for buyer
 * and seller) and the following steps:
 *
 * 1. S sends the [StateAndRef] pointing to what they want to sell to B, along with info about the price they require
 *    B to pay. For example this has probably been agreed on an exchange.
 * 2. B sends to S a [SignedTransaction] that includes the state as input, B's cash as input, the state with the new
 *    owner key as output, and any change cash as output. It contains a single signature from B but isn't valid because
 *    it lacks a signature from S authorising movement of the asset.
 * 3. S signs it and hands the now finalised SignedWireTransaction back to B.
 *
 * Assuming no malicious termination, they both end the protocol being in posession of a valid, signed transaction
 * that represents an atomic asset swap.
 *
 * Note that it's the *seller* who initiates contact with the buyer, not vice-versa as you might imagine.
 *
 * To initiate the protocol, use either the [runBuyer] or [runSeller] methods, depending on which side of the trade
 * your node is taking. These methods return a future which will complete once the trade is over and a fully signed
 * transaction is available: you can either block your thread waiting for the protocol to complete by using
 * [ListenableFuture.get] or more usefully, register a callback that will be invoked when the time comes.
 *
 * To see an example of how to use this class, look at the unit tests.
 */
object TwoPartyTradeProtocol {
    val TRADE_TOPIC = "platform.trade"

    fun runSeller(smm: StateMachineManager, timestampingAuthority: LegallyIdentifiableNode,
                  otherSide: SingleMessageRecipient, assetToSell: StateAndRef<OwnableState>, price: Amount,
                  myKeyPair: KeyPair, buyerSessionID: Long): ListenableFuture<SignedTransaction> {
        val seller = Seller(otherSide, timestampingAuthority, assetToSell, price, myKeyPair, buyerSessionID)
        return smm.add("${TRADE_TOPIC}.seller", seller)
    }

    fun runBuyer(smm: StateMachineManager, timestampingAuthority: LegallyIdentifiableNode,
                 otherSide: SingleMessageRecipient, acceptablePrice: Amount, typeToBuy: Class<out OwnableState>,
                 sessionID: Long): ListenableFuture<SignedTransaction> {
        val buyer = Buyer(otherSide, timestampingAuthority.identity, acceptablePrice, typeToBuy, sessionID)
        return smm.add("${TRADE_TOPIC}.buyer", buyer)
    }

    class UnacceptablePriceException(val givenPrice: Amount) : Exception()
    class AssetMismatchException(val expectedTypeName: String, val typeName: String) : Exception() {
        override fun toString() = "The submitted asset didn't match the expected type: $expectedTypeName vs $typeName"
    }

    // This object is serialised to the network and is the first protocol message the seller sends to the buyer.
    class SellerTradeInfo(
            val assetForSale: StateAndRef<OwnableState>,
            val price: Amount,
            val sellerOwnerKey: PublicKey,
            val sessionID: Long
    )

    class SignaturesFromSeller(val timestampAuthoritySig: DigitalSignature.WithKey, val sellerSig: DigitalSignature.WithKey)

    open class Seller(val otherSide: SingleMessageRecipient,
                      val timestampingAuthority: LegallyIdentifiableNode,
                      val assetToSell: StateAndRef<OwnableState>,
                      val price: Amount,
                      val myKeyPair: KeyPair,
                      val buyerSessionID: Long,
                      override val progressTracker: ProgressTracker = Seller.tracker()) : ProtocolLogic<SignedTransaction>() {

        companion object {
            object AWAITING_PROPOSAL : ProgressTracker.Step("Awaiting transaction proposal")
            object VERIFYING : ProgressTracker.Step("Verifying transaction proposal")
            object SIGNING : ProgressTracker.Step("Signing transaction")
            object TIMESTAMPING : ProgressTracker.Step("Timestamping transaction")
            object SENDING_SIGS : ProgressTracker.Step("Sending transaction signatures to buyer")

            fun tracker() = ProgressTracker(AWAITING_PROPOSAL, VERIFYING, SIGNING, TIMESTAMPING, SENDING_SIGS)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val partialTX: SignedTransaction = receiveAndCheckProposedTransaction()

            // These two steps could be done in parallel, in theory. Our framework doesn't support that yet though.
            val ourSignature = signWithOurKey(partialTX)
            val tsaSig = timestamp(partialTX)

            return sendSignatures(partialTX, ourSignature, tsaSig)
        }

        @Suspendable
        private fun timestamp(partialTX: SignedTransaction): DigitalSignature.LegallyIdentifiable {
            progressTracker.currentStep = TIMESTAMPING
            return subProtocol(TimestampingProtocol(timestampingAuthority, partialTX.txBits))
        }

        @Suspendable
        private fun receiveAndCheckProposedTransaction(): SignedTransaction {
            progressTracker.currentStep = AWAITING_PROPOSAL

            val sessionID = random63BitValue()

            // Make the first message we'll send to kick off the protocol.
            val hello = SellerTradeInfo(assetToSell, price, myKeyPair.public, sessionID)

            val maybeSTX = sendAndReceive<SignedTransaction>(TRADE_TOPIC, otherSide, buyerSessionID, sessionID, hello)

            progressTracker.currentStep = VERIFYING

            maybeSTX.validate {
                progressTracker.nextStep()

                // Check that the tx proposed by the buyer is valid.
                val missingSigs = it.verify(throwIfSignaturesAreMissing = false)
                if (missingSigs != setOf(myKeyPair.public, timestampingAuthority.identity.owningKey))
                    throw SignatureException("The set of missing signatures is not as expected: $missingSigs")

                val wtx: WireTransaction = it.tx
                logger.trace { "Received partially signed transaction: ${it.id}" }

                checkDependencies(it)

                // This verifies that the transaction is contract-valid, even though it is missing signatures.
                serviceHub.verifyTransaction(wtx.toLedgerTransaction(serviceHub.identityService, serviceHub.storageService.attachments))

                if (wtx.outputs.sumCashBy(myKeyPair.public) != price)
                    throw IllegalArgumentException("Transaction is not sending us the right amount of cash")

                // There are all sorts of funny games a malicious secondary might play here, we should fix them:
                //
                // - This tx may attempt to send some assets we aren't intending to sell to the secondary, if
                //   we're reusing keys! So don't reuse keys!
                // - This tx may include output states that impose odd conditions on the movement of the cash,
                //   once we implement state pairing.
                //
                // but the goal of this code is not to be fully secure (yet), but rather, just to find good ways to
                // express protocol state machines on top of the messaging layer.

                return it
            }
        }

        @Suspendable
        private fun checkDependencies(stx: SignedTransaction) {
            // Download and check all the transactions that this transaction depends on, but do not check this
            // transaction itself.
            val dependencyTxIDs = stx.tx.inputs.map { it.txhash }.toSet()
            subProtocol(ResolveTransactionsProtocol(dependencyTxIDs, otherSide))
        }

        @Suspendable
        open fun signWithOurKey(partialTX: SignedTransaction): DigitalSignature.WithKey {
            progressTracker.currentStep = SIGNING
            return myKeyPair.signWithECDSA(partialTX.txBits)
        }

        @Suspendable
        private fun sendSignatures(partialTX: SignedTransaction, ourSignature: DigitalSignature.WithKey,
                                   tsaSig: DigitalSignature.LegallyIdentifiable): SignedTransaction {
            progressTracker.currentStep = SENDING_SIGS
            val fullySigned = partialTX + tsaSig + ourSignature

            logger.trace { "Built finished transaction, sending back to secondary!" }

            send(TRADE_TOPIC, otherSide, buyerSessionID, SignaturesFromSeller(tsaSig, ourSignature))
            return fullySigned
        }
    }

    open class Buyer(val otherSide: SingleMessageRecipient,
                     val timestampingAuthority: Party,
                     val acceptablePrice: Amount,
                     val typeToBuy: Class<out OwnableState>,
                     val sessionID: Long) : ProtocolLogic<SignedTransaction>() {

        object RECEIVING : ProgressTracker.Step("Waiting for seller trading info")
        object VERIFYING : ProgressTracker.Step("Verifying seller assets")
        object SIGNING : ProgressTracker.Step("Generating and signing transaction proposal")
        object SWAPPING_SIGNATURES : ProgressTracker.Step("Swapping signatures with the seller")

        override val progressTracker = ProgressTracker(RECEIVING, VERIFYING, SIGNING, SWAPPING_SIGNATURES)

        @Suspendable
        override fun call(): SignedTransaction {
            val tradeRequest = receiveAndValidateTradeRequest()

            progressTracker.currentStep = SIGNING
            val (ptx, cashSigningPubKeys) = assembleSharedTX(tradeRequest)
            val stx = signWithOurKeys(cashSigningPubKeys, ptx)

            // exitProcess(0)

            val signatures = swapSignaturesWithSeller(stx, tradeRequest.sessionID)

            logger.trace { "Got signatures from seller, verifying ... " }
            val fullySigned = stx + signatures.timestampAuthoritySig + signatures.sellerSig
            fullySigned.verify()

            logger.trace { "Signatures received are valid. Trade complete! :-)" }
            return fullySigned
        }

        @Suspendable
        private fun receiveAndValidateTradeRequest(): SellerTradeInfo {
            progressTracker.currentStep = RECEIVING
            // Wait for a trade request to come in on our pre-provided session ID.
            val maybeTradeRequest = receive<SellerTradeInfo>(TRADE_TOPIC, sessionID)

            progressTracker.currentStep = VERIFYING
            maybeTradeRequest.validate {
                // What is the seller trying to sell us?
                val asset = it.assetForSale.state
                val assetTypeName = asset.javaClass.name
                logger.trace { "Got trade request for a $assetTypeName: ${it.assetForSale}" }

                // Check the start message for acceptability.
                check(it.sessionID > 0)
                if (it.price > acceptablePrice)
                    throw UnacceptablePriceException(it.price)
                if (!typeToBuy.isInstance(asset))
                    throw AssetMismatchException(typeToBuy.name, assetTypeName)

                // Check the transaction that contains the state which is being resolved.
                // We only have a hash here, so if we don't know it already, we have to ask for it.
                subProtocol(ResolveTransactionsProtocol(setOf(it.assetForSale.ref.txhash), otherSide))

                return it
            }
        }

        @Suspendable
        private fun swapSignaturesWithSeller(stx: SignedTransaction, theirSessionID: Long): SignaturesFromSeller {
            progressTracker.currentStep = SWAPPING_SIGNATURES
            logger.trace { "Sending partially signed transaction to seller" }

            // TODO: Protect against the seller terminating here and leaving us in the lurch without the final tx.

            return sendAndReceive<SignaturesFromSeller>(TRADE_TOPIC, otherSide, theirSessionID, sessionID, stx).validate { it }
        }

        private fun signWithOurKeys(cashSigningPubKeys: List<PublicKey>, ptx: TransactionBuilder): SignedTransaction {
            // Now sign the transaction with whatever keys we need to move the cash.
            for (k in cashSigningPubKeys) {
                val priv = serviceHub.keyManagementService.toPrivate(k)
                ptx.signWith(KeyPair(k, priv))
            }

            return ptx.toSignedTransaction(checkSufficientSignatures = false)
        }

        private fun assembleSharedTX(tradeRequest: SellerTradeInfo): Pair<TransactionBuilder, List<PublicKey>> {
            val ptx = TransactionBuilder()
            // Add input and output states for the movement of cash, by using the Cash contract to generate the states.
            val wallet = serviceHub.walletService.currentWallet
            val cashStates = wallet.statesOfType<Cash.State>()
            val cashSigningPubKeys = Cash().generateSpend(ptx, tradeRequest.price, tradeRequest.sellerOwnerKey, cashStates)
            // Add inputs/outputs/a command for the movement of the asset.
            ptx.addInputState(tradeRequest.assetForSale.ref)
            // Just pick some new public key for now. This won't be linked with our identity in any way, which is what
            // we want for privacy reasons: the key is here ONLY to manage and control ownership, it is not intended to
            // reveal who the owner actually is. The key management service is expected to derive a unique key from some
            // initial seed in order to provide privacy protection.
            val freshKey = serviceHub.keyManagementService.freshKey()
            val (command, state) = tradeRequest.assetForSale.state.withNewOwner(freshKey.public)
            ptx.addOutputState(state)
            ptx.addCommand(command, tradeRequest.assetForSale.state.owner)

            // And add a request for timestamping: it may be that none of the contracts need this! But it can't hurt
            // to have one.
            ptx.setTime(Instant.now(), timestampingAuthority, 30.seconds)
            return Pair(ptx, cashSigningPubKeys)
        }
    }
}