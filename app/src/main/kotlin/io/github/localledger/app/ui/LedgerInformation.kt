package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import io.github.localledger.app.data.PeriodStatement
import io.github.localledger.core.*
import java.math.BigDecimal
import java.math.BigInteger

internal data class StatementMetric(val label: String, val amount: BigInteger, val type: TransactionType?=null)
internal fun CashFlow.metrics(type: TransactionType?): List<StatementMetric> = when (type) {
    TransactionType.EXPENSE -> listOf(StatementMetric(appString(R.string.ui_expense), expense,TransactionType.EXPENSE))
    TransactionType.INCOME -> listOf(StatementMetric(appString(R.string.ui_income), income,TransactionType.INCOME))
    TransactionType.TRANSFER -> listOf(StatementMetric(appString(R.string.ui_transferred), transfer,TransactionType.TRANSFER), StatementMetric(appString(R.string.ui_received_b48e2), received,TransactionType.TRANSFER), StatementMetric(appString(R.string.ui_net_fees), transferCost))
    null -> listOf(StatementMetric(appString(R.string.ui_income), income,TransactionType.INCOME), StatementMetric(appString(R.string.ui_expense), expense,TransactionType.EXPENSE), StatementMetric(appString(R.string.ui_net), net))
}
internal fun statementAmount(value: BigInteger) = "¥ ${BigDecimal(value, 2).toPlainString()}"

@Composable
internal fun StatementMetrics(metrics: List<StatementMetric>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        AdaptiveItems(metrics,260.dp) { _,value ->
            Row(Modifier.fillMaxWidth().heightIn(min=28.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(value.label,Modifier.weight(1f).alignByBaseline(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(statementAmount(value.amount),Modifier.weight(2f).alignByBaseline(),textAlign=TextAlign.End,
                    color=financialColors(LocalLedgerDark.current).let { when(value.type){TransactionType.INCOME->it.income;TransactionType.EXPENSE->it.expense;TransactionType.TRANSFER->it.transfer;null->MaterialTheme.colorScheme.onSurface} },
                    style=MaterialTheme.typography.titleSmall.copy(fontFeatureSettings="tnum"))
            }
        }
    }
}

@Composable
internal fun DayStatement(date: String, totals: CashFlow?) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (totals != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val amounts = buildList {
                if (totals.expenseCount > 0) add(appString(R.string.ui_expense_90334, statementAmount(totals.expense)))
                if (totals.incomeCount > 0) add(appString(R.string.ui_income_6e0e9, statementAmount(totals.income)))
                if (totals.transferCount > 0) add(appString(R.string.ui_transferred_4d294, statementAmount(totals.transfer)))
            }
            amounts.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun MonthlyStatement(period: PeriodStatement, type: TransactionType?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(appString(R.string.ui_count_d442a, period.key, period.totals.count), style = MaterialTheme.typography.titleSmall)
        StatementMetrics(period.totals.metrics(type))
        if (type == null && period.totals.transferCount > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(appString(R.string.ui_transferred_4d294, statementAmount(period.totals.transfer)), style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.ui_net_fees_aa894, statementAmount(period.totals.transferCost)), style = MaterialTheme.typography.bodySmall)
        }
    }
}
