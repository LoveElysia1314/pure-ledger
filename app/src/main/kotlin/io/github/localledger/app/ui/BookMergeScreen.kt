package io.github.localledger.app.ui
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localledger.app.LedgerGraph
import io.github.localledger.app.data.BookMergePlan
import io.github.localledger.core.LedgerCatalog
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal

@Composable
internal fun BookMergeScreen(source: String, catalog: LedgerCatalog, graph: LedgerGraph, onBack: () -> Unit, onDone: () -> Unit) {
    val targets = catalog.books.filterNot { it.id == source }
    var target by rememberSaveable(source) { mutableStateOf(targets.firstOrNull()?.id.orEmpty()) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<BookMergePlan?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val busy by graph.busy.collectAsStateWithLifecycle()
    val actionError by graph.actionError.collectAsStateWithLifecycle()
    BackHandler(!busy, onBack)
    LaunchedEffect(source, target, retry) {
        plan = null; failure = null
        if (target.isNotEmpty()) try { plan = graph.previewBookMerge(source, target) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failure = appString(R.string.ui_could_not_load_the_merge_preview_try_again) }
    }
    Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
        Surface(Modifier.weight(1f, fill = false), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(catalog.books.find { it.id == source }?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                ChoiceField(appString(R.string.ui_merge_into), target, targets.map { it.id to it.name }, !busy) { target = it; graph.clearActionError() }
                val preview = plan
                if (preview != null) {
                    Text(appString(R.string.ui_move_entries_entries_after_merging, preview.count, preview.count + preview.targetCount))
                    Text(appString(R.string.ui_expense_income, BigDecimal(preview.expense, 2).toPlainString(), BigDecimal(preview.income, 2).toPlainString()), style = MaterialTheme.typography.bodyMedium)
                    if (preview.transfers > 0) Text(appString(R.string.ui_transfers, preview.transfers), style = MaterialTheme.typography.bodyMedium)
                    Text(appString(R.string.ui_matching_categories_use_the_destination_settings_o, preview.sourceName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                (actionError ?: failure)?.let { error ->
                    Text(localizedMessage(error), color = MaterialTheme.colorScheme.error)
                    TextButton({ retry++; graph.clearActionError() }, enabled = !busy) { Text(appString(R.string.ui_refresh_preview)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onBack, enabled = !busy) { Text(appString(R.string.ui_cancel)) }
            Spacer(Modifier.weight(1f))
            Button({ plan?.let { graph.mergeBooks(it, onDone) } }, enabled = !busy && plan != null && actionError == null) { Text(appString(R.string.ui_merge_ledgers)) }
        }
    }
}
