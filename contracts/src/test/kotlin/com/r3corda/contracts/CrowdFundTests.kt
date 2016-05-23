package com.r3corda.contracts

import com.r3corda.contracts.testing.CASH
import com.r3corda.contracts.testing.`owned by`
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.days
import com.r3corda.core.node.services.testing.MockStorageService
import com.r3corda.core.seconds
import com.r3corda.core.testing.*
import org.junit.Test
import java.time.Instant
import java.util.*
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrowdFundTests {
    val CF_1 = CrowdFund.State(
            campaign = CrowdFund.Campaign(
                    owner = MINI_CORP_PUBKEY,
                    name = "kickstart me",
                    target = 1000.DOLLARS,
                    closingTime = TEST_TX_TIME + 7.days
            ),
            closed = false,
            pledges = ArrayList<CrowdFund.Pledge>(),
            notary = DUMMY_NOTARY
    )

    val attachments = MockStorageService().attachments

    @Test
    fun `key mismatch at issue`() {
        transactionGroup {
            transaction {
                output { CF_1 }
                arg(DUMMY_PUBKEY_1) { CrowdFund.Commands.Register() }
                timestamp(TEST_TX_TIME)
            }

            expectFailureOfTx(1, "the transaction is signed by the owner of the crowdsourcing")
        }
    }

    @Test
    fun `closing time not in the future`() {
        transactionGroup {
            transaction {
                output { CF_1.copy(campaign = CF_1.campaign.copy(closingTime = TEST_TX_TIME - 1.days)) }
                arg(MINI_CORP_PUBKEY) { CrowdFund.Commands.Register() }
                timestamp(TEST_TX_TIME)
            }

            expectFailureOfTx(1, "the output registration has a closing time in the future")
        }
    }

    @Test
    fun ok() {
        raiseFunds().verify()
    }

    private fun raiseFunds(): TransactionGroupDSL<CrowdFund.State> {
        return transactionGroupFor {
            roots {
                transaction(1000.DOLLARS.CASH `owned by` ALICE_PUBKEY label "alice's $1000")
            }

            // 1. Create the funding opportunity
            transaction {
                output("funding opportunity") { CF_1 }
                arg(MINI_CORP_PUBKEY) { CrowdFund.Commands.Register() }
                timestamp(TEST_TX_TIME)
            }

            // 2. Place a pledge
            transaction {
                input ("funding opportunity")
                input("alice's $1000")
                output ("pledged opportunity") {
                    CF_1.copy(
                            pledges = CF_1.pledges + CrowdFund.Pledge(ALICE_PUBKEY, 1000.DOLLARS)
                    )
                }
                output { 1000.DOLLARS.CASH `owned by` MINI_CORP_PUBKEY }
                arg(ALICE_PUBKEY) { Cash.Commands.Move() }
                arg(ALICE_PUBKEY) { CrowdFund.Commands.Pledge() }
                timestamp(TEST_TX_TIME)
            }

            // 3. Close the opportunity, assuming the target has been met
            transaction {
                input ("pledged opportunity")
                output ("funded and closed") { "pledged opportunity".output.copy(closed = true) }
                arg(MINI_CORP_PUBKEY) { CrowdFund.Commands.Close() }
                timestamp(time = TEST_TX_TIME + 8.days)
            }
        }
    }

    fun cashOutputsToWallet(vararg states: Cash.State): Pair<LedgerTransaction, List<StateAndRef<Cash.State>>> {
        val ltx = LedgerTransaction(emptyList(), emptyList(), listOf(*states), emptyList(), SecureHash.randomSHA256())
        return Pair(ltx, states.mapIndexed { index, state -> StateAndRef(state, StateRef(ltx.id, index)) })
    }

    @Test
    fun `raise more funds using output-state generation functions`() {
        // MiniCorp registers a crowdfunding of $1,000, to close in 7 days.
        val registerTX: LedgerTransaction = run {
            // craftRegister returns a partial transaction
            val ptx = CrowdFund().generateRegister(MINI_CORP.ref(123), 1000.DOLLARS, "crowd funding", TEST_TX_TIME + 7.days, DUMMY_NOTARY).apply {
                setTime(TEST_TX_TIME, DUMMY_NOTARY, 30.seconds)
                signWith(MINI_CORP_KEY)
                signWith(DUMMY_NOTARY_KEY)
            }
            ptx.toSignedTransaction().verifyToLedgerTransaction(MOCK_IDENTITY_SERVICE, attachments)
        }

        // let's give Alice some funds that she can invest
        val (aliceWalletTX, aliceWallet) = cashOutputsToWallet(
                200.DOLLARS.CASH `owned by` ALICE_PUBKEY,
                500.DOLLARS.CASH `owned by` ALICE_PUBKEY,
                300.DOLLARS.CASH `owned by` ALICE_PUBKEY
        )

        // Alice pays $1000 to MiniCorp to fund their campaign.
        val pledgeTX: LedgerTransaction = run {
            val ptx = TransactionBuilder()
            CrowdFund().generatePledge(ptx, registerTX.outRef(0), ALICE_PUBKEY)
            Cash().generateSpend(ptx, 1000.DOLLARS, MINI_CORP_PUBKEY, aliceWallet)
            ptx.setTime(TEST_TX_TIME, DUMMY_NOTARY, 30.seconds)
            ptx.signWith(ALICE_KEY)
            ptx.signWith(DUMMY_NOTARY_KEY)
            // this verify passes - the transaction contains an output cash, necessary to verify the fund command
            ptx.toSignedTransaction().verifyToLedgerTransaction(MOCK_IDENTITY_SERVICE, attachments)
        }

        // Won't be validated.
        val (miniCorpWalletTx, miniCorpWallet) = cashOutputsToWallet(
                900.DOLLARS.CASH `owned by` MINI_CORP_PUBKEY,
                400.DOLLARS.CASH `owned by` MINI_CORP_PUBKEY
        )
        // MiniCorp closes their campaign.
        fun makeFundedTX(time: Instant): LedgerTransaction {
            val ptx = TransactionBuilder()
            ptx.setTime(time, DUMMY_NOTARY, 30.seconds)
            CrowdFund().generateClose(ptx, pledgeTX.outRef(0), miniCorpWallet)
            ptx.signWith(MINI_CORP_KEY)
            ptx.signWith(DUMMY_NOTARY_KEY)
            return ptx.toSignedTransaction().verifyToLedgerTransaction(MOCK_IDENTITY_SERVICE, attachments)
        }

        val tooEarlyClose = makeFundedTX(TEST_TX_TIME + 6.days)
        val validClose = makeFundedTX(TEST_TX_TIME + 8.days)

        val e = assertFailsWith(TransactionVerificationException::class) {
            TransactionGroup(setOf(registerTX, pledgeTX, tooEarlyClose), setOf(miniCorpWalletTx, aliceWalletTX)).verify()
        }
        assertTrue(e.cause!!.message!!.contains("the closing date has past"))

        // This verification passes
        TransactionGroup(setOf(registerTX, pledgeTX, validClose), setOf(aliceWalletTX)).verify()
    }
}