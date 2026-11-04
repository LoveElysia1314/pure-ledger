package io.github.localledger.core

import java.time.*
import kotlin.test.*

class DateRangePresetTest {
    @Test fun `presets preserve account book type and search while replacing only date bounds`() {
        val today=LocalDate.of(2026,1,1)
        val zone=ZoneId.of("Asia/Shanghai")
        val original=LedgerQuery(text="coffee",bookId="travel",accountId="cash",type=TransactionType.EXPENSE,minimumMinor=123)
        val week=original.inPreset(DateRangePreset.THIS_WEEK,today,zone)
        assertEquals(LocalDate.of(2025,12,29),week.firstDate(zone));assertEquals(LocalDate.of(2026,1,4),week.lastDate(zone))
        assertEquals(original,week.inPreset(DateRangePreset.ALL,today,zone))
        val last=original.inPreset(DateRangePreset.LAST_MONTH,today,zone)
        assertEquals(YearMonth.of(2025,12),last.calendarMonth(zone))
        val seven=original.inPreset(DateRangePreset.LAST_SEVEN_DAYS,today,zone)
        assertEquals(today.minusDays(6),seven.firstDate(zone));assertEquals(today,seven.lastDate(zone))
    }
    @Test fun `today uses local calendar boundaries across daylight saving and year includes leap day`() {
        val zone=ZoneId.of("America/New_York")
        val today=LedgerQuery().inPreset(DateRangePreset.TODAY,LocalDate.of(2026,3,8),zone)
        assertEquals(23,Duration.ofMillis(today.untilMs!!-today.fromMs!!).toHours())
        val year=LedgerQuery().inPreset(DateRangePreset.THIS_YEAR,LocalDate.of(2024,2,29),zone)
        assertEquals(LocalDate.of(2024,1,1),year.firstDate(zone));assertEquals(LocalDate.of(2024,12,31),year.lastDate(zone))
    }
}
