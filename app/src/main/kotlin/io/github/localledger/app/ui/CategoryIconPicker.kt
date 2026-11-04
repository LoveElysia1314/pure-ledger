package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.localledger.core.CategoryIcons

@Composable
internal fun CategoryIconPicker(selected: String, enabled: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val cellWidth = if (LocalDensity.current.fontScale > 1.3f) 112.dp else 76.dp
    val columns = (maxWidth / cellWidth).toInt().coerceIn(2, 8)
    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = appString(R.string.ui_category_icons) }) {
        items(CategoryIcons.choices.chunked(columns), key = { it.first().first }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (key, _) ->
                    val name = io.github.localledger.app.CatalogLabels.icon(key)
                    Column(Modifier.weight(1f).heightIn(min = 64.dp)
                        .background(if (key == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                        .selectable(key == selected, enabled = enabled, role = Role.RadioButton) { onSelect(key) }
                        .semantics { contentDescription = appString(R.string.ui_icon, name) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            CategoryBadge(name, selected = key == selected, icon = key)
                            if (key == selected) Icon(LedgerIcons.Check, null, Modifier.align(Alignment.TopEnd).size(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
  }
}
