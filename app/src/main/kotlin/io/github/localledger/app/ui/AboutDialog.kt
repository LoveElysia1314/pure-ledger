package io.github.localledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.app.update.UpdateState
import io.github.localledger.app.update.UpdateMessage
import io.github.localledger.app.update.AppRelease

@Composable
internal fun AboutDialog(version: String, onLicenses: () -> Unit, onDismiss: () -> Unit,
                         update: UpdateState = UpdateState(), onCheck: () -> Unit = {},
                         onAutomatic: (Boolean) -> Unit = {}, onRelease: (AppRelease) -> Unit = {}) {
    AlertDialog(onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium) {
                    Icon(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(56.dp).padding(8.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Text(appString(R.string.app_name), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
        }, text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(appString(R.string.ui_version_ngpl_3_0_only, version), style = MaterialTheme.typography.bodyMedium)
                Text("GPL-3.0-only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onLicenses) { Text(appString(R.string.ui_open_source_licenses)) }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(update.automatic, role = Role.Switch, onValueChange = onAutomatic),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(appString(R.string.ui_automatic_updates), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(update.automatic, null)
                }
                TextButton(onCheck, enabled = !update.checking, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appString(if (update.checking) R.string.ui_checking_updates else R.string.ui_check_updates))
                }
                if (update.releases.isNotEmpty()) {
                    Text(appString(R.string.ui_update_available, update.releases.first().version.toString()), style = MaterialTheme.typography.titleSmall)
                    update.releases.forEach { release ->
                        TextButton({ onRelease(release) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(appString(R.string.ui_open_release, release.source.host.label))
                        }
                    }
                } else if (!update.checking && update.result != null) {
                    Text(appString(if (update.result == UpdateMessage.FAILED) R.string.ui_update_failed else R.string.ui_no_updates),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }, confirmButton = { TextButton(onDismiss) { Text(appString(R.string.ui_close)) } })
}
