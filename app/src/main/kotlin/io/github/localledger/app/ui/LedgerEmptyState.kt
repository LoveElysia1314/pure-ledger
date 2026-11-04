package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.localledger.core.*
import java.time.YearMonth

@Composable
internal fun LedgerEmptyState(query: LedgerQuery, offerAutomaticSettings: Boolean, onCreate: () -> Unit,
                              onClear: () -> Unit, onAllTime: () -> Unit, onAutomaticSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val hasDates = query.fromMs != null || query.untilMs != null
        Text(when {
            query.hasConditions -> appString(R.string.ui_no_matching_entries)
            query.month() == YearMonth.now() -> appString(R.string.ui_no_entries_this_month)
            hasDates -> appString(R.string.ui_no_entries_in_this_period)
            else -> appString(R.string.ui_no_entries_yet)
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.hasConditions) TextButton(onClear, Modifier.heightIn(min = 48.dp)) { Text(appString(R.string.ui_clear_filters_657d9)) }
            else TextButton(onCreate, Modifier.heightIn(min = 48.dp)) { Text(appString(R.string.ui_add_entry)) }
            if (hasDates) TextButton(onAllTime, Modifier.heightIn(min = 48.dp)) { Text(appString(R.string.ui_view_all_time)) }
            else if (offerAutomaticSettings && !query.hasConditions) TextButton(onAutomaticSettings, Modifier.heightIn(min = 48.dp)) { Text(appString(R.string.ui_automatic_entry_settings)) }
        }
    }
}
