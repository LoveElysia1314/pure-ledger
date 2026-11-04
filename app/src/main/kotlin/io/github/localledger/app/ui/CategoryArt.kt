package io.github.localledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import io.github.localledger.core.CategoryPresets

/** Stored icon identities are preserved; the pinned Lucide subset supplies consistent artwork. */
@Composable
internal fun CategoryBadge(name: String, modifier: Modifier = Modifier, selected: Boolean = false, icon: String? = null) {
    val symbol = icon ?: CategoryPresets.symbol(name)
    val color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else categoryTint(symbol, LocalLedgerDark.current)
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else color.copy(alpha = .105f)
    Box(modifier.size(36.dp).background(bg, LedgerShapes.icon).padding(6.dp)) {
        androidx.compose.material3.Icon(CategoryGlyphs[symbol], contentDescription = null, tint = color, modifier = Modifier.fillMaxSize())
    }
}
