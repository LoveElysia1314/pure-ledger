package io.github.localledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.localledger.app.*
import io.github.localledger.app.R
import io.github.localledger.core.*

@Composable
internal fun CategoryPicker(type: TransactionType,selected: String,catalog: LedgerCatalog,enabled: Boolean,onSelect:(String)->Unit) {
    var searching by rememberSaveable(type.name){mutableStateOf(false)}
    var search by rememberSaveable(type.name){mutableStateOf("")}
    val current=catalog.categories.find { it.id==selected }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=enabled,role=Role.Button){searching=!searching},verticalAlignment=Alignment.CenterVertically) {
            CategoryBadge(current?.displayName.orEmpty(),icon=current?.icon)
            Text(appString(R.string.ui_category),Modifier.weight(1f).padding(start=10.dp),style=MaterialTheme.typography.bodyMedium)
            Text(current?.displayName ?: CatalogLabels.other(type),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyMedium)
            Icon(LedgerIcons.Down,null,Modifier.padding(start=4.dp).size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(searching)OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text(appString(R.string.ui_search_categories))},singleLine=true,enabled=enabled)
        EntryCategoryBoard(type,selected,search,catalog,enabled,LocalDensity.current.fontScale>1.3f,
            Modifier.fillMaxWidth().height(if(searching)300.dp else 164.dp),onSelect)
        TextButton({searching=!searching;search=""},enabled=enabled){Text(appString(if(searching)R.string.ui_done_c0b3f else R.string.ui_search_44ce7))}
    }
}
