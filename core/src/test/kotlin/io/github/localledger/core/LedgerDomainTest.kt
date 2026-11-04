package io.github.localledger.core

import java.math.BigInteger
import kotlin.test.*

class LedgerDomainTest {
    private fun transfer(amount: Long = 10000, fee: Long = 200, discount: Long = 50) = EntryDraft(
        TransactionType.TRANSFER, Money(amount), "转账", 1, accountId = "from", toAccountId = "to",
        fee = Money(fee), discount = Money(discount))
    @Test fun `transfer uses legacy debit and receiving semantics`() {
        val entry = transfer()
        assertEquals(BigInteger.valueOf(-10000), entry.accountEffects()["from"])
        assertEquals(BigInteger.valueOf(9850), entry.accountEffects()["to"])
    }
    @Test fun `intermediate transfer arithmetic never overflows`() {
        val entry = transfer(Long.MAX_VALUE, 0, Long.MAX_VALUE)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, entry.transferReceived)
    }
    @Test fun `unassigned and historical entries do not invent balance changes`() {
        assertTrue(EntryDraft(TransactionType.EXPENSE, Money(100), "商户", 1).accountEffects().isEmpty())
        assertTrue(transfer().copy(affectsBalance = false).accountEffects().isEmpty())
    }
    @Test fun `expense income and edits have exact signed effects`() {
        val out = EntryDraft(TransactionType.EXPENSE, Money(101), "商户", 1, accountId = "a")
        assertEquals(BigInteger.valueOf(-101), out.accountEffects()["a"])
        assertEquals(BigInteger.valueOf(101), out.copy(type = TransactionType.INCOME).accountEffects()["a"])
    }
    @Test fun `invalid transfers and incidental fields are rejected`() {
        assertFailsWith<IllegalArgumentException> { transfer(0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { transfer().copy(toAccountId = "from") }
        assertFailsWith<IllegalArgumentException> { transfer(100, 101, 0) }
        assertFailsWith<IllegalArgumentException> { transfer().copy(accountId = null) }
        assertFailsWith<IllegalArgumentException> { transfer().copy(type = TransactionType.EXPENSE) }
    }
    @Test fun `zero value ordinary entries remain faithful without changing balances`() {
        val entry = EntryDraft(TransactionType.EXPENSE, Money(0), "", 1, accountId = "wallet")
        assertEquals(BigInteger.ZERO, entry.accountEffects()["wallet"])
        assertFailsWith<IllegalArgumentException> { entry.copy(amount = Money(-1)) }
    }
}
