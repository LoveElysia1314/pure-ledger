package io.github.localledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.core.LedgerQuery

@Composable
internal fun Modifier.selectorSurface()=clip(LedgerShapes.field)
    .background(MaterialTheme.colorScheme.surfaceContainerHigh)

@Composable
internal fun SelectorButton(value:String,description:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,period:Boolean=false) {
    ControlScale(if(period)1f else 1.25f) {
        Surface(onClick,modifier.height(48.dp).semantics{contentDescription=description;stateDescription=value},enabled=enabled,
            shape=LedgerShapes.action,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(value,Modifier.weight(1f),style=if(period)MaterialTheme.typography.titleSmall.copy(lineHeight=20.sp) else MaterialTheme.typography.titleSmall,maxLines=if(period)2 else 1,overflow=TextOverflow.Ellipsis)
                Icon(LedgerIcons.Down,null,Modifier.size(16.dp))
            }
        }
    }
}

@Composable
internal fun BrowseToolbar(book:String,query:LedgerQuery,enabled:Boolean,onBook:()->Unit,onPeriod:()->Unit,onQuery:(LedgerQuery)->Unit,onAdd:(()->Unit)?,onFilter:()->Unit) {
    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
            .padding(horizontal=12.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
          BoxWithConstraints(Modifier.fillMaxWidth()) {
            if(maxWidth>=760.dp)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                BookMenuButton(book,onBook,Modifier.weight(.42f),enabled)
                MonthNavigation(query,enabled,onQuery,onPeriod,Modifier.weight(.58f))
                if(onAdd!=null)BrowseAddButton(onAdd,enabled) else Spacer(Modifier.width(48.dp))
                IconButton(onFilter,enabled=enabled){Icon(LedgerIcons.Filter,appString(R.string.ui_filter))}
            } else Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                BookMenuButton(book,onBook,Modifier.weight(1f),enabled)
                if(onAdd!=null)BrowseAddButton(onAdd,enabled) else Spacer(Modifier.width(48.dp))
                IconButton(onFilter,enabled=enabled){Icon(LedgerIcons.Filter,appString(R.string.ui_filter))}
            }
            MonthNavigation(query,enabled,onQuery,onPeriod,Modifier.fillMaxWidth())
            }
          }
        }
    }
}

@Composable
private fun BookMenuButton(book:String,onClick:()->Unit,modifier:Modifier,enabled:Boolean) {
    Box(modifier) {
        ControlScale(1.25f) {
            Surface(onClick,Modifier.height(48.dp).semantics { contentDescription=appString(R.string.ui_choose_ledger);stateDescription=book },
                enabled=enabled,shape=LedgerShapes.action,color=MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(book,Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Icon(LedgerIcons.Down,null,Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun BrowseAddButton(onClick:()->Unit,enabled:Boolean) {
    IconButton(onClick,enabled=enabled,modifier=Modifier.size(48.dp)){Icon(LedgerIcons.Add,appString(R.string.ui_add_entry))}
}
