package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Keep the Material picker and actions within the actual window, including 320dp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LedgerDateDialog(onDismiss: () -> Unit, canConfirm: Boolean, onConfirm: () -> Unit,
                              content: @Composable () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 360.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            color = DatePickerDefaults.colors().containerColor) {
            Column(Modifier.heightIn(max = 560.dp)) {
                Box(Modifier.weight(1f, fill = false)) { content() }
                Row(Modifier.align(Alignment.End).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(appString(R.string.ui_cancel)) }
                    TextButton(onConfirm, Modifier.heightIn(min = 48.dp), enabled = canConfirm) { Text(appString(R.string.ui_ok)) }
                }
            }
        }
    }
}
