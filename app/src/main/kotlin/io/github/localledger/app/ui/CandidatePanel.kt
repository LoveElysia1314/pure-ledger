package io.github.localledger.app.ui
import io.github.localledger.app.appString


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.localledger.app.R
import io.github.localledger.app.PendingCandidate
import io.github.localledger.core.*

/** An opaque, bounded surface: identity, readable content and actions stay in one window. */
@Composable
internal fun CandidatePanel(candidate: PendingCandidate, catalog: LedgerCatalog, memory: SelectionMemory,
                            modifier: Modifier = Modifier, onSave: (ConfirmedEntry) -> Unit,
                            onCancel: () -> Unit, onRetry: () -> Unit, onExpandedChange: (Boolean) -> Unit) {
  key(candidate.token) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val savedForm = rememberSaveableStateHolder()
    var summaryType by rememberSaveable { mutableStateOf(candidate.preparation?.draft?.type?.name ?: candidate.candidate.type.name) }
    var summaryAmount by rememberSaveable { mutableStateOf((candidate.preparation?.draft?.amount ?: candidate.candidate.amount).decimal().toPlainString()) }
    var summaryMerchant by rememberSaveable { mutableStateOf(candidate.preparation?.draft?.merchant ?: candidate.candidate.merchant) }
    var fieldEditorActive by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val maximumHeight = (LocalConfiguration.current.screenHeightDp * if (fieldEditorActive) .80f else .72f).dp
    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
        if (!expanded) keyboard?.hide()
    }
    Surface(modifier = modifier.semantics { paneTitle = appString(R.string.ui_confirm_entry) }, shape = LedgerShapes.sheet,
        color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)), shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)) {
          if (expanded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_brand_mark), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ expanded = false }, enabled = !candidate.saving,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = appString(R.string.ui_collapse_entry_window) }) { Text(appString(R.string.ui_collapse)) }
            }
            HorizontalDivider(thickness=.5.dp,color = MaterialTheme.colorScheme.outlineVariant)
            savedForm.SaveableStateProvider("editor") {
                CandidateEditor(candidate, onSave, onCancel, showTitle = false, catalog = catalog, memory = memory, onRetry = onRetry, compact = true, onFieldEditorChanged = { fieldEditorActive = it },
                    onSummaryChanged = { type, amount, merchant -> summaryType = type.name; summaryAmount = amount; summaryMerchant = merchant })
            }
          } else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (candidate.preparation?.repeated == true) DuplicateBadge()
                if (LocalDensity.current.fontScale > 1.3f) Text(appString(R.string.app_name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (summaryAmount.isBlank()) appString(R.string.ui_no_amount_entered) else "${if (summaryType == TransactionType.INCOME.name) "+" else "−"} ¥ $summaryAmount", style = MaterialTheme.typography.titleMedium)
                val caption = if (LocalDensity.current.fontScale > 1.3f) summaryMerchant else listOf(appString(R.string.app_name), summaryMerchant).filter(String::isNotBlank).joinToString(" · ")
                if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton({ onExpandedChange(true); expanded = true }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = appString(R.string.ui_expand_entry_window) }) { Text(appString(R.string.ui_expand)) }
            IconButton(onCancel, enabled = !candidate.saving, modifier = Modifier.semantics { contentDescription = appString(R.string.ui_cancel_entry) }) { Icon(LedgerIcons.Close, null) }
          }
        }
    }
  }
}
