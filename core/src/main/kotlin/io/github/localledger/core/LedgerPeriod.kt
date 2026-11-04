package io.github.localledger.core

import java.time.YearMonth
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun LedgerQuery.inMonth(month: YearMonth?, zone: ZoneId = ZoneId.systemDefault()): LedgerQuery = copy(
    fromMs = month?.atDay(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
    untilMs = month?.plusMonths(1)?.atDay(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
)

fun LedgerQuery.inDateRange(first: LocalDate?, lastInclusive: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): LedgerQuery {
    require(first == null || lastInclusive == null || !lastInclusive.isBefore(first)) { "结束日期需不早于开始日期" }
    return copy(fromMs = first?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        untilMs = lastInclusive?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli())
}

fun LedgerQuery.inMonthRange(first: YearMonth?, last: YearMonth?, zone: ZoneId=ZoneId.systemDefault()): LedgerQuery {
    require(first==null || last==null || first<=last) { "结束月份需不早于开始月份" }
    require(listOfNotNull(first,last).all{it.year in 1..9999}) { "年份需在 1–9999 之间" }
    return inDateRange(first?.atDay(1),last?.atEndOfMonth(),zone)
}

fun LedgerQuery.inYear(year:Int,zone:ZoneId=ZoneId.systemDefault())=inMonthRange(YearMonth.of(year,1),YearMonth.of(year,12),zone)

fun LedgerQuery.calendarYear(zone:ZoneId=ZoneId.systemDefault()):Int? {
    val first=firstDate(zone) ?: return null
    return first.year.takeIf{first.monthValue==1 && first.dayOfMonth==1 && inYear(it,zone).let{q->q.fromMs==fromMs && q.untilMs==untilMs}}
}

/** Browsing whole calendar ranges advances by months or years, independent of month lengths. */
fun LedgerQuery.shiftBrowsePeriod(direction:Int,today:YearMonth=YearMonth.now(),zone:ZoneId=ZoneId.systemDefault()):LedgerQuery {
    require(direction==-1 || direction==1)
    calendarYear(zone)?.let{return inYear(it+direction,zone)}
    val first=firstDate(zone);val last=lastDate(zone)
    if(first!=null && last!=null && first.dayOfMonth==1 && last==YearMonth.from(last).atEndOfMonth()) {
        val start=YearMonth.from(first);val end=YearMonth.from(last)
        val count=ChronoUnit.MONTHS.between(start,end)+1
        return inMonthRange(start.plusMonths(count*direction),end.plusMonths(count*direction),zone)
    }
    return shiftPeriod(direction,today,zone)
}

fun LedgerQuery.calendarMonth(zone: ZoneId = ZoneId.systemDefault()): YearMonth? {
    val from = fromMs ?: return null
    val month = YearMonth.from(Instant.ofEpochMilli(from).atZone(zone))
    val full = inMonth(month, zone)
    return month.takeIf { full.fromMs == fromMs && full.untilMs == untilMs }
}

fun LedgerQuery.firstDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate? = fromMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
fun LedgerQuery.lastDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate? = untilMs?.let { Instant.ofEpochMilli(it).minusMillis(1).atZone(zone).toLocalDate() }

/** Calendar days, rather than 24-hour milliseconds, preserve ranges across daylight-saving transitions. */
fun LedgerQuery.shiftPeriod(direction: Int, today: YearMonth = YearMonth.now(), zone: ZoneId = ZoneId.systemDefault()): LedgerQuery {
    require(direction == -1 || direction == 1)
    val month = calendarMonth(zone)
    if (month != null || fromMs == null && untilMs == null) return inMonth((month ?: today).plusMonths(direction.toLong()), zone)
    val first = firstDate(zone) ?: return this
    val last = lastDate(zone) ?: return this
    val days = ChronoUnit.DAYS.between(first, last) + 1
    return inDateRange(first.plusDays(days * direction), last.plusDays(days * direction), zone)
}
