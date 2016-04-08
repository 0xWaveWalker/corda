package core

import contracts.Cash
import core.crypto.SecureHash
import core.testutils.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TransactionGroupTests {
    val A_THOUSAND_POUNDS = Cash.State(MINI_CORP.ref(1, 2, 3), 1000.POUNDS, MINI_CORP_PUBKEY)

    @Test
    fun success() {
        transactionGroup {
            roots {
                transaction(A_THOUSAND_POUNDS label "£1000")
            }

            transaction {
                input("£1000")
                output("alice's £1000") { A_THOUSAND_POUNDS `owned by` ALICE }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
            }

            transaction {
                input("alice's £1000")
                arg(ALICE) { Cash.Commands.Move() }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Exit(1000.POUNDS) }
            }

            verify()
        }
    }

    @Test
    fun conflict() {
        transactionGroup {
            val t = transaction {
                output("cash") { A_THOUSAND_POUNDS }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Issue() }
            }

            val conflict1 = transaction {
                input("cash")
                val HALF = A_THOUSAND_POUNDS.copy(amount = 500.POUNDS) `owned by` BOB
                output { HALF }
                output { HALF }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
            }

            verify()

            // Alice tries to double spend back to herself.
            val conflict2 = transaction {
                input("cash")
                val HALF = A_THOUSAND_POUNDS.copy(amount = 500.POUNDS) `owned by` ALICE
                output { HALF }
                output { HALF }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
            }

            assertNotEquals(conflict1, conflict2)

            val e = assertFailsWith(TransactionConflictException::class) {
                verify()
            }
            assertEquals(StateRef(t.id, 0), e.conflictRef)
            assertEquals(setOf(conflict1, conflict2), setOf(e.tx1.toWireTransaction(), e.tx2.toWireTransaction()))
        }
    }

    @Test
    fun disconnected() {
        // Check that if we have a transaction in the group that doesn't connect to anything else, it's rejected.
        val tg = transactionGroup {
            transaction {
                output("cash") { A_THOUSAND_POUNDS }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Issue() }
            }

            transaction {
                input("cash")
                output { A_THOUSAND_POUNDS `owned by` BOB }
            }
        }

        // We have to do this manually without the DSL because transactionGroup { } won't let us create a tx that
        // points nowhere.
        val ref = StateRef(SecureHash.randomSHA256(), 0)
        tg.txns += TransactionBuilder().apply {
            addInputState(ref)
            addOutputState(A_THOUSAND_POUNDS)
            addCommand(Cash.Commands.Move(), BOB)
        }.toWireTransaction()

        val e = assertFailsWith(TransactionResolutionException::class) {
            tg.verify()
        }
        assertEquals(e.hash, ref.txhash)
    }

    @Test
    fun duplicatedInputs() {
        // Check that a transaction cannot refer to the same input more than once.
        transactionGroup {
            roots {
                transaction(A_THOUSAND_POUNDS label "£1000")
            }

            transaction {
                input("£1000")
                input("£1000")
                output { A_THOUSAND_POUNDS.copy(amount = A_THOUSAND_POUNDS.amount * 2) }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
            }

            assertFailsWith(TransactionConflictException::class) {
                verify()
            }
        }
    }

    @Test
    fun signGroup() {
        val signedTxns: List<SignedTransaction> = transactionGroup {
            transaction {
                output("£1000") { A_THOUSAND_POUNDS }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Issue() }
            }

            transaction {
                input("£1000")
                output("alice's £1000") { A_THOUSAND_POUNDS `owned by` ALICE }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Move() }
            }

            transaction {
                input("alice's £1000")
                arg(ALICE) { Cash.Commands.Move() }
                arg(MINI_CORP_PUBKEY) { Cash.Commands.Exit(1000.POUNDS) }
            }
        }.signAll()

        // Now go through the conversion -> verification path with them.
        val ltxns = signedTxns.map { it.verifyToLedgerTransaction(MockIdentityService, MockStorageService().attachments) }.toSet()
        TransactionGroup(ltxns, emptySet()).verify(MockContractFactory)
    }
}