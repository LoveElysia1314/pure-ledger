package io.github.localledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

internal data class TrendPoint(val first: LocalDate, val last: LocalDate, val totals: CashFlow) {
    val label get() = if(first==last)first.toString() else "$first – $last"
}
private fun sumFlows(values: Iterable<CashFlow>) = values.fold(CashFlow()) { a,b -> CashFlow(
    a.expense+b.expense,a.income+b.income,a.transfer+b.transfer,a.transferCost+b.transferCost,
    a.expenseCount+b.expenseCount,a.incomeCount+b.incomeCount,a.transferCount+b.transferCount) }

/** All-result statements drive the chart; visible ledger rows may be only the first page. */
internal fun trendPoints(snapshot: LedgerSnapshot): List<TrendPoint> {
    val actual=snapshot.days.associate { LocalDate.parse(it.key) to it.totals }
    if(actual.isEmpty())return emptyList()
    val first=snapshot.query.firstDate() ?: actual.keys.min()
    val last=snapshot.query.lastDate() ?: actual.keys.max()
    val days=ChronoUnit.DAYS.between(first,last)+1
    if(days<=62)return (0 until days.toInt()).map { offset ->
        val day=first.plusDays(offset.toLong());TrendPoint(day,day,actual[day] ?: CashFlow())
    }
    val from=YearMonth.from(first);val through=YearMonth.from(last)
    val months=ChronoUnit.MONTHS.between(from,through)+1
    val step=((months+59)/60).coerceAtLeast(1)
    val bins=snapshot.monthly.groupBy { ChronoUnit.MONTHS.between(from,YearMonth.parse(it.key))/step }
    return (0 until ((months+step-1)/step).toInt()).map { index ->
        val start=from.plusMonths(index*step).atDay(1).coerceAtLeast(first)
        val end=from.plusMonths((index+1)*step-1).atEndOfMonth().coerceAtMost(last)
        TrendPoint(start,end,sumFlows(bins[index.toLong()].orEmpty().map { it.totals }))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StatisticsTrend(snapshot: LedgerSnapshot, table:Boolean, onPeriod: (LocalDate,LocalDate) -> Unit) {
    val points=remember(snapshot.days,snapshot.monthly,snapshot.query){trendPoints(snapshot)}
    var selected by rememberSaveable(snapshot.query) { mutableIntStateOf(-1) }
    val index=selected.coerceIn(-1,points.lastIndex).takeIf { it>=0 } ?: points.indexOfLast { it.totals.count>0 }.coerceAtLeast(0)
    val type=snapshot.query.type
    val expense=financialColors(LocalLedgerDark.current).let { if(type==TransactionType.TRANSFER)it.transfer else it.expense }
    val income=financialColors(LocalLedgerDark.current).income
    val guide=MaterialTheme.colorScheme.outlineVariant
    fun primary(point: TrendPoint)=when(type){TransactionType.INCOME->point.totals.income;TransactionType.TRANSFER->point.totals.transfer;else->point.totals.expense}
    Column(Modifier.fillMaxWidth().padding(horizontal=LedgerSpacing.page),verticalArrangement=Arrangement.spacedBy(LedgerSpacing.related)) {
        if(points.isEmpty()) { Text(appString(R.string.ui_no_trend_data),Modifier.padding(vertical=16.dp));return@Column }
        if(table) {
            StatisticsTimeList(snapshot,points,type,onPeriod)
        } else {
            val maximum=points.maxOf { primary(it).max(if(type==null)it.totals.income else BigInteger.ZERO) }
            Text(statementAmount(maximum),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val picked=points[index]
            Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {
                contentDescription=appString(R.string.ui_time_series)
                stateDescription=picked.label
                customActions=listOf(
                    CustomAccessibilityAction(appString(R.string.ui_previous_period)){selected=(index-1).coerceAtLeast(0);true},
                    CustomAccessibilityAction(appString(R.string.ui_next_period)){selected=(index+1).coerceAtMost(points.lastIndex);true})
            }.pointerInput(points,type) { detectTapGestures { position -> selected=(position.x/size.width*points.size).toInt().coerceIn(0,points.lastIndex) } }) {
                val bottom=size.height-4.dp.toPx()
                val slot=size.width/points.size
                fun height(value: BigInteger)=if(maximum.signum()==0)0f else BigDecimal(value).divide(BigDecimal(maximum),10,RoundingMode.HALF_UP).toFloat()*(bottom-8.dp.toPx())
                drawLine(guide,Offset(0f,bottom),Offset(size.width,bottom),1.dp.toPx())
                points.forEachIndexed { at,point ->
                    val width=(slot*.7f).coerceAtMost(36.dp.toPx())
                    val x=at*slot+(slot-width)/2
                    val highlight=(slot*.85f).coerceAtMost(56.dp.toPx())
                    if(at==index)drawRect(guide.copy(alpha=.12f),Offset(at*slot+(slot-highlight)/2,0f),Size(highlight,bottom))
                    val h=height(primary(point))
                    drawRect(if(type==TransactionType.INCOME)income else expense,Offset(x,bottom-h),Size(if(type==null)width*.45f else width,h))
                    if(type==null) { val h2=height(point.totals.income);drawRect(income,Offset(x+width*.55f,bottom-h2),Size(width*.45f,h2)) }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=if(points.size==1)Arrangement.Center else Arrangement.SpaceBetween) {
                Text(points.first().first.toString(),style=MaterialTheme.typography.labelSmall)
                if(points.size>1)Text(points.last().last.toString(),style=MaterialTheme.typography.labelSmall)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                if(type==null || type==TransactionType.EXPENSE)Text(appString(R.string.ui_expense),color=expense,style=MaterialTheme.typography.labelMedium)
                if(type==null || type==TransactionType.INCOME)Text(appString(R.string.ui_income),color=income,style=MaterialTheme.typography.labelMedium)
                if(type==TransactionType.TRANSFER)Text(appString(R.string.ui_transferred),color=expense,style=MaterialTheme.typography.labelMedium)
            }
            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(picked.label,style=MaterialTheme.typography.labelLarge)
                    StatementMetrics(picked.totals.metrics(type))
                    Text(appString(R.string.ui_count,picked.totals.count),style=MaterialTheme.typography.labelMedium)
                    TextButton({onPeriod(picked.first,picked.last)},Modifier.heightIn(min=48.dp)){Text(appString(if(YearMonth.from(picked.first)==YearMonth.from(picked.last))R.string.ui_month_entries else R.string.ui_period_details))}
                }
            }
        }
    }
}
