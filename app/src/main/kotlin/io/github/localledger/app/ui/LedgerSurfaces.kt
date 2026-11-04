package io.github.localledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

@Composable
internal fun Section(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()) { Column(content = content) }
}

@Composable
internal fun SettingsDivider()=HorizontalDivider(Modifier.padding(horizontal=16.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun SettingLine(title: String, value: String, onClick: () -> Unit, enabled: Boolean = true, stackValue: Boolean = false) {
    val stacked = stackValue || (LocalDensity.current.fontScale > 1.3f && value.length > 4)
    ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (stacked && value.isNotEmpty()) { { Text(value, style = MaterialTheme.typography.bodySmall) } } else null,
        trailingContent = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!stacked && value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodyMedium)
            Icon(LedgerIcons.Next, null, Modifier.size(16.dp))
        } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}
