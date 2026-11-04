package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.localledger.app.LedgerGraph
import io.github.localledger.app.data.LedgerRow
import io.github.localledger.core.*
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.BigInteger

@Composable
internal fun CategoryBills(name: String, type: TransactionType, query: LedgerQuery, catalog: LedgerCatalog,
                          graph: LedgerGraph, listState: LazyListState, onEntry: (Long) -> Unit) {
    var rows by remember { mutableStateOf<List<LedgerRow>?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val largeText = LocalDensity.current.fontScale > 1.3f
    LaunchedEffect(name, type, query, retry) {
        rows = null; error = false
        try { rows = graph.categoryRows(name, type, query) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.ui_value_fed2d, name, typeTitle(type)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
            rows?.let { values ->
                val total = values.fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(row.amount.minor) }
                Text(appString(R.string.ui_entries, values.size, BigDecimal(total, 2).toPlainString()), Modifier.padding(vertical = 8.dp))
            }
        }
        if (error) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(appString(R.string.ui_could_not_load_entries), Modifier.weight(1f)); TextButton({ retry++ }) { Text(appString(R.string.ui_retry)) }
        } else if (rows == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (rows!!.isEmpty()) item { Text(appString(R.string.ui_no_entries_yet), Modifier.padding(16.dp)) }
            items(rows!!, key = { it.id }) { row -> TransactionLine(row, largeText, catalog, showDate = true) { onEntry(row.id) } }
        }
    }
}
