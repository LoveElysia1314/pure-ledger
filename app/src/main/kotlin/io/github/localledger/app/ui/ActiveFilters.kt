package io.github.localledger.app.ui
import io.github.localledger.app.displayName
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.localledger.core.*

internal fun LedgerQuery.conditionNames(catalog: LedgerCatalog): List<String> = buildList {
    val lower = minimumMinor; val upper = maximumMinor
    if (text.isNotBlank()) add("“${text.trim()}”")
    type?.let { add(typeTitle(it)) }
    categoryId?.let { id -> catalog.categories.find { it.id == id }?.let { add(it.displayName) } }
    accountId?.let { id -> catalog.accounts.find { it.id == id }?.let { add(it.name) } }
    when {
        lower != null && upper != null -> add("¥ ${Money(lower).decimal().toPlainString()}–${Money(upper).decimal().toPlainString()}")
        lower != null -> add("≥ ¥ ${Money(lower).decimal().toPlainString()}")
        upper != null -> add("≤ ¥ ${Money(upper).decimal().toPlainString()}")
    }
}

@Composable
internal fun ActiveFilters(query: LedgerQuery, catalog: LedgerCatalog, enabled: Boolean, onEdit: () -> Unit, onClear: () -> Unit) {
    if (!query.hasConditions) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onEdit).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(query.conditionNames(catalog).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClear, enabled = enabled) { Icon(LedgerIcons.Close, appString(R.string.ui_clear_filters), Modifier.size(18.dp)) }
        }
    }
}
