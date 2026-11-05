package io.github.localledger.app.ui
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.localledger.app.PendingCandidate
import io.github.localledger.core.*

@Composable
fun CandidateEditor(pending: PendingCandidate, onSave: (ConfirmedEntry) -> Unit, onCancel: () -> Unit,
                    showTitle: Boolean = true, catalog: LedgerCatalog = LedgerCatalog(),
                    memory: SelectionMemory = SelectionMemory(), onRetry: () -> Unit = {},
                    compact: Boolean = false, onFieldEditorChanged: (Boolean) -> Unit = {},
                    onSummaryChanged: ((TransactionType, String, String) -> Unit)? = null) {
    val source = pending.candidate
    val preparation = pending.preparation
    if (preparation == null) {
        Column(Modifier.padding(24.dp)) {
            if (pending.error == null) CircularProgressIndicator() else {
                Text(localizedMessage(pending.error), color = MaterialTheme.colorScheme.error)
                TextButton(onRetry) { Text(appString(R.string.ui_retry)) }
            }
        }
        return
    }
    EntryForm(preparation.draft,
        "candidate:${pending.token}", catalog, pending.saving, pending.error, allowTransfer = false,
        allowTimeEdit = true, inlineTimeEdit = true, confirmDiscard = false, compact = compact,
        memory = memory, onFieldEditorChanged = onFieldEditorChanged, status = if (preparation.repeated) appString(R.string.ui_duplicate) else null, onSummaryChanged = onSummaryChanged,
        title = if (showTitle) appString(R.string.ui_confirm_entry) else null, onSave = { draft ->
            try {
                onSave(ConfirmedEntry(source, draft.amount, draft.merchant, draft.type, draft.note,
                    draft.bookId, draft.accountId, draft.categoryId, draft.occurredAtMs, draft.affectsBalance))
            } catch (e: IllegalArgumentException) { throw e }
        }, onCancel = onCancel)
}

/** Repeated entries require attention in every presentation of the confirmation form. */
@Composable
internal fun DuplicateBadge(text: String = appString(R.string.ui_duplicate), modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelLarge)
}
