package io.github.localledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Metadata flows into complete rows without clipped tags or paging controls. */
@Composable
internal fun EntryTags(modifier:Modifier=Modifier,content:@Composable FlowRowScope.()->Unit) {
    FlowRow(modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),content=content)
}
