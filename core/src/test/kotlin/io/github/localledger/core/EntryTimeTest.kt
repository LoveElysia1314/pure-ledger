package io.github.localledger.core

import java.time.*
import kotlin.test.*

class EntryTimeTest {
    private fun day(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    @Test fun utcCalendarDateUsesTheDisplayZoneAndKeepsSecondsAndMilliseconds() {
        val original = Instant.parse("2026-10-31T04:34:56.789Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-01T01:45:56.789Z").toEpochMilli(), EntryTime.change(original, day("2026-11-01"), 9, 45, ZoneId.of("Asia/Shanghai")))
        assertEquals(Instant.parse("2026-11-01T17:45:56.789Z").toEpochMilli(), EntryTime.change(original, day("2026-11-01"), 9, 45, ZoneId.of("America/Los_Angeles")))
    }
    @Test fun nonexistentDstClockIsRejectedAndOverlapKeepsOriginalOffset() {
        val zone = ZoneId.of("America/New_York")
        assertFailsWith<IllegalArgumentException> { EntryTime.change(Instant.parse("2026-03-07T12:00:00Z").toEpochMilli(), day("2026-03-08"), 2, 30, zone) }
        assertEquals(Instant.parse("2026-11-01T06:10:05.006Z").toEpochMilli(), EntryTime.change(Instant.parse("2026-11-01T06:40:05.006Z").toEpochMilli(), day("2026-11-01"), 1, 10, zone))
    }
    @Test fun correctedTimeDoesNotRewriteRecognitionIdentityOrEvidence() {
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(100), "合成商户", "synthetic-time-001", null, "v1", 1000)
        val hash = source.evidenceHash()
        val confirmed = ConfirmedEntry(source, occurredAtMs = 86401000)
        assertEquals(86401000L, confirmed.draft().occurredAtMs); assertEquals(hash, confirmed.source.evidenceHash())
        assertEquals(1000L, confirmed.source.occurredAtMs)
    }
    @Test fun hiddenCategoryIsNotSuggestedButItsHistoricalReferenceRemainsValid() {
        val hidden = LedgerCategory("cat", TransactionType.EXPENSE, "三餐", hidden = true)
        val choice = EntryChoice(DEFAULT_BOOK, hidden.id, "account")
        val catalog = LedgerCatalog(listOf(LedgerBook(DEFAULT_BOOK, "默认账本")), listOf(hidden), listOf(LedgerAccount("account", "现金", java.math.BigInteger.ZERO, true)))
        assertNull(SelectionPolicy.resolve(catalog, SelectionMemory(), TransactionType.EXPENSE, merchant = choice).categoryId)
        assertEquals("account", SelectionPolicy.resolve(catalog, SelectionMemory(), TransactionType.EXPENSE, merchant = choice).accountId)
        assertTrue(CategoryIcons.keys.containsAll(CategoryPresets.known.map { it.symbol }))
        assertEquals(hidden.id, EntryDraft(TransactionType.EXPENSE, Money(1), "", 1, categoryId = hidden.id).categoryId)
    }
}
