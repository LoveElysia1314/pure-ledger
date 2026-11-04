package io.github.localledger.core

import java.time.*
import kotlin.test.*

class MonthBrowsingTest {
    private val zone=ZoneId.of("America/New_York")
    @Test fun wholeMonthsIncludeTheirLastDayAndKeepFinancialConditions() {
        val query=LedgerQuery(text="note",bookId="book",type=TransactionType.INCOME,minimumMinor=123L)
        val range=query.inMonthRange(YearMonth.of(2024,2),YearMonth.of(2024,3),zone)
        assertEquals(LocalDate.of(2024,2,1),range.firstDate(zone));assertEquals(LocalDate.of(2024,3,31),range.lastDate(zone))
        assertEquals(query,range.copy(fromMs=null,untilMs=null))
        assertEquals(LocalDate.of(2024,4,1).atStartOfDay(zone).toInstant().toEpochMilli(),range.untilMs)
    }
    @Test fun yearsAndMonthRangesShiftByCalendarUnitsAndRoundTrip() {
        val year=LedgerQuery().inYear(2024,zone)
        val next=year.shiftBrowsePeriod(1,zone=zone)
        assertEquals(2025,next.calendarYear(zone));assertEquals(year,next.shiftBrowsePeriod(-1,zone=zone))
        val months=LedgerQuery().inMonthRange(YearMonth.of(2025,11),YearMonth.of(2026,2),zone)
        assertEquals(YearMonth.of(2026,3),YearMonth.from(months.shiftBrowsePeriod(1,zone=zone).firstDate(zone)))
        assertEquals(months,months.shiftBrowsePeriod(1,zone=zone).shiftBrowsePeriod(-1,zone=zone))
    }
    @Test fun invertedAndOutOfBoundsRangesAreRejectedBeforeChangingTheQuery() {
        assertFailsWith<IllegalArgumentException>{LedgerQuery().inMonthRange(YearMonth.of(2026,5),YearMonth.of(2026,4))}
        assertFailsWith<IllegalArgumentException>{LedgerQuery().inYear(10000)}
        assertFailsWith<IllegalArgumentException>{LedgerQuery().inMonth(YearMonth.of(9999,12)).shiftBrowsePeriod(1)}
    }
}
