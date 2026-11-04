package io.github.localledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.localledger.app.AppLanguage
import io.github.localledger.app.R
import io.github.localledger.app.appString

@Composable
internal fun LanguagePicker(selected: AppLanguage?, onSelect: (AppLanguage?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.language)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
                (listOf<AppLanguage?>(null) + AppLanguage.entries).forEach { language ->
                    Row(Modifier.fillMaxWidth().selectable(language == selected, role = Role.RadioButton, onClick = { onSelect(language) })
                        .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = language == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(language?.nativeName ?: appString(R.string.ui_system_default), Modifier.weight(1f))
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.ui_cancel)) } })
}
