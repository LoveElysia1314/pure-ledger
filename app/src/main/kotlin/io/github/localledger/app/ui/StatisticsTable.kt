package io.github.localledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.app.data.LedgerSnapshot
import io.github.localledger.core.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun StatisticsTimeList(snapshot:LedgerSnapshot,points:List<TrendPoint>,type:TransactionType?,onPeriod:(LocalDate,LocalDate)->Unit) {
    val monthly=snapshot.monthly.takeIf{it.size>1 && snapshot.query.calendarMonth()==null}
    val rows=monthly?.map { period->val month=YearMonth.parse(period.key);TrendPoint(month.atDay(1),month.atEndOfMonth(),period.totals) } ?: points.filter{it.totals.count>0}
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tabular=maxWidth>=720.dp && LocalDensity.current.fontScale<=1.3f && rows.all{row->row.totals.metrics(type).all{statementAmount(it.amount).length<=16}}
        if(tabular)Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(appString(R.string.ui_date),Modifier.width(160.dp),style=MaterialTheme.typography.labelLarge)
                    Text(appString(R.string.ui_entry_count_label),Modifier.width(56.dp),textAlign=TextAlign.End,style=MaterialTheme.typography.labelLarge)
                    CashFlow().metrics(type).forEach{Text(it.label,Modifier.weight(1f),textAlign=TextAlign.End,style=MaterialTheme.typography.labelLarge)}
                }
                rows.forEachIndexed { index,row ->
                    if(index>0)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.3f))
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(role=Role.Button){onPeriod(row.first,row.last)}.padding(horizontal=16.dp,vertical=12.dp),
                        horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(if(monthly!=null)YearMonth.from(row.first).toString() else row.label,Modifier.width(160.dp),style=MaterialTheme.typography.bodyMedium)
                        Text(row.totals.count.toString(),Modifier.width(56.dp),textAlign=TextAlign.End,style=MaterialTheme.typography.bodyMedium)
                        row.totals.metrics(type).forEach{Text(statementAmount(it.amount),Modifier.weight(1f),textAlign=TextAlign.End,style=MaterialTheme.typography.titleSmall.copy(fontFeatureSettings="tnum"))}
                    }
                }
            }
        } else if(monthly!=null)AdaptiveItems(monthly) { _,period ->
            Surface(Modifier.fillMaxWidth().clickable(role=Role.Button){val month=YearMonth.parse(period.key);onPeriod(month.atDay(1),month.atEndOfMonth())},
                color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) { MonthlyStatement(period,type) }
        } else AdaptiveItems(rows) { _,point ->
            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button){onPeriod(point.first,point.last)}.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(point.label,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                        Text(appString(R.string.ui_count,point.totals.count),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatementMetrics(point.totals.metrics(type))
                }
            }
        }
    }
}
