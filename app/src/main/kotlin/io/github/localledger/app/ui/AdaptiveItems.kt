package io.github.localledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Horizontal reading items grow by usable column width, with a bounded reading measure. */
@Composable
internal fun <T> AdaptiveItems(items:List<T>,minimum:Dp=320.dp,content:@Composable (Int,T)->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellMinimum=if(LocalDensity.current.fontScale>1.3f)minimum*1.25f else minimum
        val columns=((maxWidth+8.dp)/(cellMinimum+8.dp)).toInt().coerceIn(1,3)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEachIndexed { row,values ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    values.forEachIndexed { index,item->Box(Modifier.weight(1f)){content(row*columns+index,item)} }
                    repeat(columns-values.size){Spacer(Modifier.weight(1f))}
                }
            }
        }
    }
}
