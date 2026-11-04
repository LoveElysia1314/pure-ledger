package io.github.localledger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LedgerEntryTest {
    private val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1234),
        "合成商户", "synthetic-001", "2026-10-31 12:00:00", "test-v1", 1793419200000)

    @Test fun `confirmation preserves the source and exact edited amount`() {
        val edited = ConfirmedEntry(source, Money.parseCny("90071992547409.91"), "修改后的商户", note = "合成备注")
        assertEquals(9007199254740991L, edited.amount.minor)
        assertEquals(1234L, edited.source.amount.minor)
        assertEquals(source.evidenceHash(), edited.source.evidenceHash())
    }
    @Test fun `source identity conflict changes evidence`() {
        assertNotEquals(source.evidenceHash(), source.copy(amount = Money(1235)).evidenceHash())
        assertNotEquals(source.evidenceHash(), source.copy(occurredAtMs = source.occurredAtMs!! + 1).evidenceHash())
    }
    @Test fun `incomplete zero negative or transfer records cannot be confirmed`() {
        assertEquals(null, ConfirmedEntry(source.copy(externalId = null)).source.reliableKey)
        assertFailsWith<IllegalArgumentException> { ConfirmedEntry(source.copy(occurredAtMs = null)) }
        assertFailsWith<IllegalArgumentException> { ConfirmedEntry(source, Money(0)) }
        assertFailsWith<IllegalArgumentException> { ConfirmedEntry(source, Money(-1)) }
        assertFailsWith<IllegalArgumentException> { ConfirmedEntry(source, merchant = " ") }
        assertFailsWith<IllegalArgumentException> { ConfirmedEntry(source, type = TransactionType.TRANSFER) }
    }
}
