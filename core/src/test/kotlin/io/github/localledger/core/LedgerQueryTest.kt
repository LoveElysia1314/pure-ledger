package io.github.localledger.core

import java.time.*
import kotlin.test.*

class LedgerQueryTest {
    private val zone = ZoneId.of("America/New_York")
    @Test fun `single inclusive date becomes a half open local day across daylight saving`() {
        val day = LocalDate.of(2026,3,8)
        val query = LedgerQuery().inDateRange(day,day,zone)
        assertEquals(23L, Duration.ofMillis(query.untilMs!!-query.fromMs!!).toHours())
        assertEquals(day,query.firstDate(zone)); assertEquals(day,query.lastDate(zone))
        assertNull(query.calendarMonth(zone))
        val next=query.shiftPeriod(1,zone=zone)
        assertEquals(day.plusDays(1),next.firstDate(zone)); assertEquals(day.plusDays(1),next.lastDate(zone))
        assertEquals(query,next.shiftPeriod(-1,zone=zone))
    }
    @Test fun `arbitrary range is not mistaken for a calendar month and steps by local days`() {
        val query=LedgerQuery().inDateRange(LocalDate.of(2026,1,29),LocalDate.of(2026,2,2),zone)
        assertNull(query.calendarMonth(zone))
        val next=query.shiftPeriod(1,zone=zone)
        assertEquals(LocalDate.of(2026,2,3),next.firstDate(zone)); assertEquals(LocalDate.of(2026,2,7),next.lastDate(zone))
        assertEquals(query,next.shiftPeriod(-1,zone=zone))
        assertEquals(YearMonth.of(2024,2), LedgerQuery().inDateRange(LocalDate.of(2024,2,1),LocalDate.of(2024,2,29),zone).calendarMonth(zone))
    }
    @Test fun `open ranges are retained and inverted dates or amount bounds are rejected`() {
        val start=LocalDate.of(2026,1,1)
        assertEquals(start,LedgerQuery().inDateRange(start,null,zone).firstDate(zone))
        assertEquals(start,LedgerQuery().inDateRange(null,start,zone).lastDate(zone))
        assertFailsWith<IllegalArgumentException> { LedgerQuery().inDateRange(start,start.minusDays(1)) }
        assertFailsWith<IllegalArgumentException> { LedgerQuery(minimumMinor=11,maximumMinor=10) }
        assertFailsWith<IllegalArgumentException> { LedgerQuery(minimumMinor=-1) }
        assertFalse(LedgerQuery().hasConditions)
        assertTrue(LedgerQuery(minimumMinor=0,maximumMinor=Long.MAX_VALUE).hasConditions)
    }
    @Test fun `clearing conditions preserves book and date scope`() {
        val query=LedgerQuery(text="coffee",bookId="trip",type=TransactionType.EXPENSE,categoryId="food",accountId="cash",minimumMinor=0,maximumMinor=100)
            .inMonth(YearMonth.of(2026,2),zone)
        assertEquals(LedgerQuery(bookId="trip").inMonth(YearMonth.of(2026,2),zone),query.clearConditions())
        assertFalse(query.clearConditions().hasConditions)
    }
}
