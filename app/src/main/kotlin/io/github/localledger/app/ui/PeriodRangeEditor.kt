package io.github.localledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.core.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

internal fun DateRangePreset.title() = appString(when(this) {
    DateRangePreset.TODAY -> R.string.ui_today
    DateRangePreset.THIS_WEEK -> R.string.ui_this_week
    DateRangePreset.LAST_SEVEN_DAYS -> R.string.ui_recent_seven_days
    DateRangePreset.THIS_MONTH -> R.string.ui_this_month
    DateRangePreset.LAST_MONTH -> R.string.ui_last_month
    DateRangePreset.THIS_YEAR -> R.string.ui_this_year
    DateRangePreset.ALL -> R.string.ui_all_time
})

@Composable
internal fun PeriodRangeEditor(query:LedgerQuery,onApply:(LedgerQuery)->Unit,onDismiss:()->Unit) {
    var first by rememberSaveable{mutableStateOf((query.firstDate()?.let(YearMonth::from) ?: YearMonth.now()).toString())}
    var last by rememberSaveable{mutableStateOf((query.lastDate()?.let(YearMonth::from) ?: YearMonth.now()).toString())}
    var editing by rememberSaveable{mutableStateOf<String?>(null)}
    val result=runCatching{query.inMonthRange(YearMonth.parse(first),YearMonth.parse(last))}
    if(editing!=null) { MonthOnlyPicker(YearMonth.parse(if(editing=="first")first else last),{
        if(editing=="first")first=it.toString() else last=it.toString();editing=null
    },{editing=null})
        return
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(appString(R.string.ui_month_range))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(appString(R.string.ui_start_month),style=MaterialTheme.typography.labelMedium)
            SelectorButton(first,appString(R.string.ui_choose_start_month),{editing="first"},Modifier.fillMaxWidth())
            Text(appString(R.string.ui_end_month),style=MaterialTheme.typography.labelMedium)
            SelectorButton(last,appString(R.string.ui_choose_end_month),{editing="last"},Modifier.fillMaxWidth())
            if(result.isFailure)Text(io.github.localledger.app.localizedMessage(result.exceptionOrNull()?.message.orEmpty()),color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton({onApply(result.getOrThrow())},enabled=result.isSuccess){Text(appString(R.string.ui_apply))}},
        dismissButton={TextButton(onDismiss){Text(appString(R.string.ui_cancel))}})
}
