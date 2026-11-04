package io.github.localledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.app.displayName
import io.github.localledger.app.localizedMessage
import io.github.localledger.core.*
import kotlinx.coroutines.isActive

private const val HIDDEN_GROUP = "category:hidden-group"

@Composable
internal fun CategoryManager(catalog: LedgerCatalog, busy: Boolean, error: String?,
    onEdit: (String) -> Unit, onCreate: (TransactionType) -> Unit,
    onArrange: (TransactionType, List<String>, List<String>, () -> Unit) -> Unit,
    onHidden: (TransactionType, List<String>, Boolean, () -> Unit) -> Unit,
    onRestore: (TransactionType, () -> Unit) -> Unit,
    onSortingChanged: (Boolean) -> Unit = {}) {
    var income by rememberSaveable { mutableStateOf(false) }
    val type=if(income)TransactionType.INCOME else TransactionType.EXPENSE
    val display=categoryDisplay()
    val categories=catalog.categories.filter { it.type==type }
    val byId=categories.associateBy { it.id }
    fun originalOrder()=categories.filterNot { it.hidden }.map { it.id }+HIDDEN_GROUP+categories.filter { it.hidden }.map { it.id }
    var ids by rememberSaveable(type) { mutableStateOf(originalOrder()) }
    var search by rememberSaveable { mutableStateOf("") }
    var batch by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(type) { mutableStateOf(emptyList<String>()) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    var grabX by remember { mutableFloatStateOf(0f) }
    var scrollDelta by remember { mutableFloatStateOf(0f) }
    val list=rememberLazyGridState()
    val edge=with(androidx.compose.ui.platform.LocalDensity.current){56.dp.toPx()}
    val step=with(androidx.compose.ui.platform.LocalDensity.current){12.dp.toPx()}
    LaunchedEffect(categories,error) { if(dragging==null){ids=originalOrder();selected=selected.filter { it in byId }} }
    LaunchedEffect(batch,dragging) { onSortingChanged(batch || dragging!=null) }
    DisposableEffect(Unit){onDispose{onSortingChanged(false)}}
    BackHandler(batch && !busy){batch=false;selected=emptyList()}
    fun commit(order: List<String>, done: () -> Unit = {}) {
        val boundary=order.indexOf(HIDDEN_GROUP)
        if(boundary>=0)onArrange(type,order.take(boundary),order.drop(boundary+1),done)
    }
    fun moveTo(id: String, target: Int) {
        val from=ids.indexOf(id)
        if(from>=0 && target in ids.indices && from!=target)ids=ids.toMutableList().apply { add(target,removeAt(from)) }
    }
    fun followPointer() {
        val source=dragging ?: return
        val info=list.layoutInfo
        val current=info.visibleItemsInfo.firstOrNull { it.key==source }
        val hit=info.visibleItemsInfo.firstOrNull { it.key!=source && it.key!="category:enabled-group" &&
            dragY>=it.offset.y && dragY<=it.offset.y+it.size.height && dragX>=it.offset.x && dragX<=it.offset.x+it.size.width } ?: return
        val from=ids.indexOf(source);val to=ids.indexOf(hit.key)
        val horizontal=current!=null && current.offset.y==hit.offset.y
        val pointer=if(horizontal)dragX else dragY
        val midpoint=if(horizontal)hit.offset.x+hit.size.width/2f else hit.offset.y+hit.size.height/2f
        if(to>=0 && ((to>from && pointer>midpoint) || (to<from && pointer<midpoint)))moveTo(source,to)
    }
    LaunchedEffect(dragging,scrollDelta) {
        while(isActive && dragging!=null && scrollDelta!=0f) {
            list.scrollBy(scrollDelta);followPointer();withFrameNanos { }
        }
    }
    fun finishSelection(){selected=emptyList()}
    fun moveSelection(first: Boolean) {
        val boundary=ids.indexOf(HIDDEN_GROUP)
        val groups=listOf(ids.take(boundary),ids.drop(boundary+1)).map { group ->
            val (picked,rest)=group.partition { it in selected };if(first)picked+rest else rest+picked
        }
        commit(groups[0]+HIDDEN_GROUP+groups[1],::finishSelection)
    }
    val hideLabel=appString(R.string.ui_hide)
    val showLabel=appString(R.string.ui_show)
    val moveUpLabel=appString(R.string.ui_move_up, "")
    val moveDownLabel=appString(R.string.ui_move_down, "")
    if(restoring)AlertDialog(onDismissRequest={if(!busy)restoring=false},title={Text(appString(R.string.ui_restore_default_categories))},
        text={Text(appString(R.string.ui_restore_category_names_icons_order_and_visibility,typeTitle(type)))},
        confirmButton={TextButton({onRestore(type){restoring=false;finishSelection()}},enabled=!busy){Text(appString(R.string.ui_restore))}},
        dismissButton={TextButton({restoring=false},enabled=!busy){Text(appString(R.string.ui_cancel))}})
    Column(Modifier.fillMaxSize().widthIn(max=720.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            LedgerTabs(listOf(appString(R.string.ui_expense),appString(R.string.ui_income)),if(income)1 else 0,
                {income=it==1;selected=emptyList()},Modifier.weight(1f),enabled={!busy})
            TextButton({batch=!batch;finishSelection()},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text(appString(if(batch)R.string.ui_done else R.string.ui_select))}
            IconButton({onCreate(type)},enabled=!busy && !batch){Icon(LedgerIcons.Add,appString(R.string.ui_add_category))}
        }
        CompactEntryField(search,{search=it},appString(R.string.ui_search_categories),Modifier.padding(horizontal=12.dp,vertical=8.dp),enabled=!busy)
        error?.let { Text(localizedMessage(it),Modifier.padding(horizontal=16.dp,vertical=4.dp),color=MaterialTheme.colorScheme.error) }
        val shown=ids.filter { it==HIDDEN_GROUP || byId[it]?.let { c -> c.displayName.contains(search,true) || c.name.contains(search,true) }==true }
        @Composable fun groupHeader(hidden: Boolean) {
            val group=ids.let { if(hidden)it.dropWhile { id->id!=HIDDEN_GROUP }.drop(1) else it.takeWhile { id->id!=HIDDEN_GROUP } }
                .filter { it in shown }
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).semantics { isTraversalGroup=true }.background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(appString(if(hidden)R.string.ui_hidden_categories else R.string.ui_active_categories,group.size),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                if(!hidden && !batch)Text(appString(R.string.ui_drag_to_reorder),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(batch)TextButton({selected=if(group.all { it in selected })selected-group.toSet() else (selected+group).distinct()},enabled=!busy && group.isNotEmpty()){
                    Text(appString(if(group.isNotEmpty() && group.all { it in selected })R.string.ui_deselect_all else R.string.ui_select_all))
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal=12.dp)) {
        val columns=categoryColumns(maxWidth,androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f,categories.map { it.displayName },display)
        LazyVerticalGrid(GridCells.Fixed(columns),Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow,MaterialTheme.shapes.medium)
            .categoryLayoutMarker(display).semantics { contentDescription=appString(R.string.ui_category_arrangement) },state=list,
            contentPadding=PaddingValues(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            item(key="category:enabled-group",span={GridItemSpan(maxLineSpan)}){groupHeader(false)}
            items(shown,key={it},span={GridItemSpan(if(it==HIDDEN_GROUP)maxLineSpan else 1)}) { id ->
                if(id==HIDDEN_GROUP)groupHeader(true)
                else byId[id]?.let { category ->
                    val boundary=ids.indexOf(HIDDEN_GROUP);val index=ids.indexOf(id);val hidden=index>boundary
                    val actions=listOf(
                        CustomAccessibilityAction(if(hidden)showLabel else hideLabel){onHidden(type,listOf(id),!hidden){};true},
                        CustomAccessibilityAction(moveUpLabel){if(index>0){val order=ids.toMutableList().apply{add(index-1,removeAt(index))};commit(order);true}else false},
                        CustomAccessibilityAction(moveDownLabel){if(index<ids.lastIndex){val order=ids.toMutableList().apply{add(index+1,removeAt(index))};commit(order);true}else false})
                    val gesture=Modifier.pointerInput(id,busy,search,type,display) {
                        if(!busy && search.isBlank())detectDragGesturesAfterLongPress(
                            onDragStart={ offset -> dragging=id;grab=offset.y;grabX=offset.x
                                val position=list.layoutInfo.visibleItemsInfo.find{it.key==id}?.offset
                                dragY=(position?.y ?: 0)+offset.y;dragX=(position?.x ?: 0)+offset.x },
                            onDragCancel={dragging=null;scrollDelta=0f;ids=originalOrder()},
                            onDragEnd={dragging=null;scrollDelta=0f;commit(ids)},
                            onDrag={change,amount->change.consume();dragY+=amount.y;dragX+=amount.x;followPointer()
                                scrollDelta=when { dragY<list.layoutInfo.viewportStartOffset+edge -> -step
                                    dragY>list.layoutInfo.viewportEndOffset-edge -> step;else->0f }
                            })
                    }
                    val action=if(batch)Modifier.toggleable(id in selected,enabled=!busy,role=Role.Checkbox){selected=if(it)(selected+id).distinct() else selected-id}
                        else Modifier.clickable(enabled=!busy,role=Role.Button){onEdit(id)}
                    Box(Modifier.fillMaxWidth().zIndex(if(dragging==id)1f else 0f)
                        .graphicsLayer { if(dragging==id){
                            val position=list.layoutInfo.visibleItemsInfo.find{it.key==id}?.offset
                            translationY=dragY-grab-(position?.y ?: 0);translationX=dragX-grabX-(position?.x ?: 0)
                        } }
                        .background(if(dragging==id || id in selected)MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,MaterialTheme.shapes.small)
                        .then(gesture).then(action).semantics { customActions=actions;contentDescription=category.displayName }
                        .heightIn(min=if(display==io.github.localledger.app.CategoryDisplay.LIST)48.dp else 88.dp)
                        .padding(horizontal=2.dp,vertical=if(display==io.github.localledger.app.CategoryDisplay.LIST)0.dp else 8.dp)) {
                        CategoryItemContent(category.displayName,category.icon,false,display,Modifier.padding(end=if(batch && display==io.github.localledger.app.CategoryDisplay.LIST)16.dp else 0.dp))
                        if(batch)Checkbox(id in selected,null,enabled=!busy,modifier=Modifier.align(Alignment.TopEnd).size(20.dp))
                    }
                }
            }
            if(shown.size==1)item(span={GridItemSpan(maxLineSpan)}){Text(appString(R.string.ui_no_matching_categories),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        }
        if(batch)Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
                Text(appString(R.string.ui_selected,selected.size),Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton({onHidden(type,selected,false,::finishSelection)},enabled=!busy && selected.any { ids.indexOf(it)>ids.indexOf(HIDDEN_GROUP) }){Text(showLabel)}
                    TextButton({onHidden(type,selected,true,::finishSelection)},enabled=!busy && selected.any { ids.indexOf(it)<ids.indexOf(HIDDEN_GROUP) }){Text(hideLabel)}
                    TextButton({moveSelection(true)},enabled=!busy && selected.isNotEmpty()){Text(appString(R.string.ui_move_first))}
                    TextButton({moveSelection(false)},enabled=!busy && selected.isNotEmpty()){Text(appString(R.string.ui_move_last))}
                }
            }
        } else TextButton({restoring=true},Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!busy){Text(appString(R.string.ui_restore_default_categories))}
    }
}
