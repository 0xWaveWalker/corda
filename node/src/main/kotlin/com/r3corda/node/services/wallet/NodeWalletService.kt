package com.r3corda.node.services.wallet

import com.codahale.metrics.Gauge
import com.r3corda.contracts.cash.Cash
import com.r3corda.core.ThreadBox
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.node.services.Wallet
import com.r3corda.core.node.services.WalletService
import com.r3corda.core.serialization.SingletonSerializeAsToken
import com.r3corda.core.utilities.loggerFor
import com.r3corda.core.utilities.trace
import com.r3corda.node.services.api.ServiceHubInternal
import java.security.PublicKey
import java.util.*
import javax.annotation.concurrent.ThreadSafe

/**
 * This class implements a simple, in memory wallet that tracks states that are owned by us, and also has a convenience
 * method to auto-generate some self-issued cash states that can be used for test trading. A real wallet would persist
 * states relevant to us into a database and once such a wallet is implemented, this scaffolding can be removed.
 */
@ThreadSafe
class NodeWalletService(private val services: ServiceHubInternal) : SingletonSerializeAsToken(), WalletService {
    private val log = loggerFor<NodeWalletService>()

    // Variables inside InnerState are protected with a lock by the ThreadBox and aren't in scope unless you're
    // inside mutex.locked {} code block. So we can't forget to take the lock unless we accidentally leak a reference
    // to wallet somewhere.
    private class InnerState {
        var wallet: Wallet = WalletImpl(emptyList<StateAndRef<OwnableState>>())
    }

    private val mutex = ThreadBox(InnerState())

    override val currentWallet: Wallet get() = mutex.locked { wallet }

    /**
     * Returns a snapshot of how much cash we have in each currency, ignoring details like issuer. Note: currencies for
     * which we have no cash evaluate to null, not 0.
     */
    override val cashBalances: Map<Currency, Amount<Currency>> get() = mutex.locked { wallet }.cashBalances

    /**
     * Returns a snapshot of the heads of LinearStates
     */
    override val linearHeads: Map<SecureHash, StateAndRef<LinearState>>
        get() = mutex.locked { wallet }.let { wallet ->
            wallet.states.filterStatesOfType<LinearState>().associateBy { it.state.thread }.mapValues { it.value }
        }

    override fun notifyAll(txns: Iterable<WireTransaction>): Wallet {
        val ourKeys = services.keyManagementService.keys.keys

        // Note how terribly incomplete this all is!
        //
        // - We don't notify anyone of anything, there are no event listeners.
        // - We don't handle or even notice invalidations due to double spends of things in our wallet.
        // - We have no concept of confidence (for txns where there is no definite finality).
        // - No notification that keys are used, for the case where we observe a spend of our own states.
        // - No ability to create complex spends.
        // - No logging or tracking of how the wallet got into this state.
        // - No persistence.
        // - Does tx relevancy calculation and key management need to be interlocked? Probably yes.
        //
        // ... and many other things .... (Wallet.java in bitcoinj is several thousand lines long)

        mutex.locked {
            // Starting from the current wallet, keep applying the transaction updates, calculating a new Wallet each
            // time, until we get to the result (this is perhaps a bit inefficient, but it's functional and easily
            // unit tested).
            wallet = txns.fold(currentWallet) { current, tx -> current.update(tx, ourKeys) }
            exportCashBalancesViaMetrics(wallet)
            return wallet
        }
    }

    private fun isRelevant(state: ContractState, ourKeys: Set<PublicKey>): Boolean {
        return if (state is OwnableState) {
            state.owner in ourKeys
        } else if (state is LinearState) {
            // It's potentially of interest to the wallet
            state.isRelevant(ourKeys)
        } else {
            false
        }
    }

    private fun Wallet.update(tx: WireTransaction, ourKeys: Set<PublicKey>): Wallet {
        val ourNewStates = tx.outputs.
                filter { isRelevant(it, ourKeys) }.
                map { tx.outRef<ContractState>(it) }

        // Now calculate the states that are being spent by this transaction.
        val consumed: Set<StateRef> = states.map { it.ref }.intersect(tx.inputs)

        // Is transaction irrelevant?
        if (consumed.isEmpty() && ourNewStates.isEmpty()) {
            log.trace { "tx ${tx.id} was irrelevant to this wallet, ignoring" }
            return this
        }

        // And calculate the new wallet.
        val newStates = states.filter { it.ref !in consumed } + ourNewStates

        log.trace {
            "Applied tx ${tx.id.prefixChars()} to the wallet: consumed ${consumed.size} states and added ${newStates.size}"
        }

        return WalletImpl(newStates)
    }

    private class BalanceMetric : Gauge<Long> {
        @Volatile var pennies = 0L
        override fun getValue(): Long? = pennies
    }

    private val balanceMetrics = HashMap<Currency, BalanceMetric>()

    private fun exportCashBalancesViaMetrics(wallet: Wallet) {
        // This is just for demo purposes. We probably shouldn't expose balances via JMX in a real node as that might
        // be commercially sensitive info that the sysadmins aren't even meant to know.
        //
        // Note: exported as pennies.
        val m = services.monitoringService.metrics
        for (balance in wallet.cashBalances) {
            val metric = balanceMetrics.getOrPut(balance.key) {
                val newMetric = BalanceMetric()
                m.register("WalletBalances.${balance.key}Pennies", newMetric)
                newMetric
            }
            metric.pennies = balance.value.pennies
        }
    }

    /**
     * Creates a random set of between (by default) 3 and 10 cash states that add up to the given amount and adds them
     * to the wallet.
     *
     * The cash is self issued with the current nodes identity, as fetched from the storage service. Thus it
     * would not be trusted by any sensible market participant and is effectively an IOU. If it had been issued by
     * the central bank, well ... that'd be a different story altogether.
     *
     * TODO: Move this out of NodeWalletService
     */
    fun fillWithSomeTestCash(notary: Party, howMuch: Amount<Currency>, atLeastThisManyStates: Int = 3,
                             atMostThisManyStates: Int = 10, rng: Random = Random()) {
        val amounts = calculateRandomlySizedAmounts(howMuch, atLeastThisManyStates, atMostThisManyStates, rng)

        val myIdentity = services.storageService.myLegalIdentity
        val myKey = services.storageService.myLegalIdentityKey

        // We will allocate one state to one transaction, for simplicities sake.
        val cash = Cash()
        val transactions = amounts.map { pennies ->
            // This line is what makes the cash self issued. We just use zero as our deposit reference: we don't need
            // this field as there's no other database or source of truth we need to sync with.
            val depositRef = myIdentity.ref(0)

            val issuance = TransactionBuilder()
            val freshKey = services.keyManagementService.freshKey()
            cash.generateIssue(issuance, Amount(pennies, howMuch.token), depositRef, freshKey.public, notary)
            issuance.signWith(myKey)

            return@map issuance.toSignedTransaction(true)
        }

        services.recordTransactions(transactions)
    }

    private fun calculateRandomlySizedAmounts(howMuch: Amount<Currency>, min: Int, max: Int, rng: Random): LongArray {
        val numStates = min + Math.floor(rng.nextDouble() * (max - min)).toInt()
        val amounts = LongArray(numStates)
        val baseSize = howMuch.pennies / numStates
        var filledSoFar = 0L
        for (i in 0..numStates - 1) {
            if (i < numStates - 1) {
                // Adjust the amount a bit up or down, to give more realistic amounts (not all identical).
                amounts[i] = baseSize + (baseSize / 2 * (rng.nextDouble() - 0.5)).toLong()
                filledSoFar += baseSize
            } else {
                // Handle inexact rounding.
                amounts[i] = howMuch.pennies - filledSoFar
            }
        }
        return amounts
    }
}
