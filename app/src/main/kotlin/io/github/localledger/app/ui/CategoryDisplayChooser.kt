package io.github.localledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.localledger.app.*
import io.github.localledger.app.R

@Composable
internal fun CategoryDisplayChooser(selected: CategoryDisplay,onSelect:(CategoryDisplay)->Unit,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(appString(R.string.ui_category_layout))},text={
        Column { CategoryDisplay.entries.forEach { display ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(display==selected,role=Role.RadioButton){onSelect(display)},verticalAlignment=Alignment.CenterVertically) {
                RadioButton(display==selected,null);Text(display.title,Modifier.padding(start=8.dp,top=8.dp,bottom=8.dp))
            }
        } }
    },confirmButton={TextButton(onDismiss){Text(appString(R.string.ui_cancel))}})
}
