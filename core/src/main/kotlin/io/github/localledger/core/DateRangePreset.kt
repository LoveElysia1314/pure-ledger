package io.github.localledger.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

enum class DateRangePreset { TODAY, THIS_WEEK, LAST_SEVEN_DAYS, THIS_MONTH, LAST_MONTH, THIS_YEAR, ALL }

fun LedgerQuery.inPreset(preset: DateRangePreset, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): LedgerQuery = when(preset) {
    DateRangePreset.TODAY -> inDateRange(today,today,zone)
    DateRangePreset.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { inDateRange(it,it.plusDays(6),zone) }
    DateRangePreset.LAST_SEVEN_DAYS -> inDateRange(today.minusDays(6),today,zone)
    DateRangePreset.THIS_MONTH -> inMonth(YearMonth.from(today),zone)
    DateRangePreset.LAST_MONTH -> inMonth(YearMonth.from(today).minusMonths(1),zone)
    DateRangePreset.THIS_YEAR -> inDateRange(today.withDayOfYear(1),today.withMonth(12).withDayOfMonth(31),zone)
    DateRangePreset.ALL -> copy(fromMs=null,untilMs=null)
}
