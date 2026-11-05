package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localledger.app.*

@Composable
internal fun AutomaticControls(graph: LedgerGraph, ready: Boolean, onEnable: (Boolean) -> Unit,
                                onNotifications: (Boolean) -> Unit, onNotificationSettings: () -> Unit) {
    val enabled by graph.enabled.collectAsStateWithLifecycle()
    val status by graph.automaticStatus.collectAsStateWithLifecycle()
    val paused by graph.paused.collectAsStateWithLifecycle()
    val notify by graph.notificationEnabled.collectAsStateWithLifecycle()
    val feedback by graph.successFeedback.collectAsStateWithLifecycle()
    val permissions by graph.runtimePermissions.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(enabled, enabled = ready || enabled, role = Role.Switch, onValueChange = onEnable)
        .padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(appString(R.string.ui_automatic_entries), style = MaterialTheme.typography.bodyLarge)
            Text(status.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status in setOf(AutomaticStatus.RUNNING, AutomaticStatus.PAUSED)) TextButton({ graph.pause(!paused) }, Modifier.heightIn(min = 48.dp)) { Text(if (paused) appString(R.string.ui_resume) else appString(R.string.ui_pause)) }
        Switch(enabled, null, enabled = ready || enabled)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(appString(R.string.ui_status_notification), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (notify && !permissions.notifications) TextButton(onNotificationSettings, Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(appString(R.string.ui_not_allowed)) }
        Switch(notify, onNotifications, Modifier.semantics { contentDescription = appString(R.string.ui_status_notification) })
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(feedback, role = Role.Switch, onValueChange = graph::setSuccessFeedback)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(appString(R.string.ui_save_confirmation), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(feedback, null)
    }
}
