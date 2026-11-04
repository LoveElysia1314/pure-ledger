package io.github.localledger.app.ui
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString
import io.github.localledger.app.R
import io.github.localledger.app.displayName


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import io.github.localledger.core.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class EntryPageValues(val type: TransactionType, val amount: String, val merchant: String, val note: String,
    val occurred: Long, val book: String, val category: String, val account: String, val destination: String,
    val fee: String, val discount: String, val affects: Boolean)

/** A fixed entry workspace: only the category catalog scrolls; secondary editors open separately. */
@Composable
internal fun EntryPage(value: EntryPageValues, catalog: LedgerCatalog, saving: Boolean, error: String?,
                       onChange: (EntryPageValues) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
                       onSaveAnother: (() -> Unit)? = null, focusNote: Boolean = false) {
    val noteFocus = remember { FocusRequester() }
    var noteFocusRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusNote) {
        if (focusNote && !noteFocusRequested) {
            noteFocusRequested = true
            noteFocus.requestFocus()
        }
    }
    var choosingType by rememberSaveable { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable(value.book, value.type.name) { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val large = LocalDensity.current.fontScale > 1.3f
    val focus = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val amountLabel = if (value.type == TransactionType.TRANSFER) appString(R.string.ui_transfer_amount_cny) else appString(R.string.ui_amount_cny)
    val date = Instant.ofEpochMilli(value.occurred).atZone(ZoneId.systemDefault())
    if (editor == "time") {
        Column(Modifier.fillMaxSize()) {
            EntryTimeEditor(value.occurred, { onChange(value.copy(occurred = it)); editor = null }, { editor = null })
        }
        return
    }
    if (editor == "details") {
        EntryDetailsEditor(value, { onChange(it); editor = null }, { editor = null })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onCancel, enabled = !saving) { Icon(LedgerIcons.Back, appString(R.string.ui_back)) }
            if (large && TransactionType.entries.any { typeTitle(it).length > 4 }) Box(Modifier.weight(1f)) {
                TextButton({ choosingType = true }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = appString(R.string.choose_entry_type); stateDescription = typeTitle(value.type) }) {
                    Text(typeTitle(value.type), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Icon(LedgerIcons.Down, null, Modifier.padding(start = 8.dp).size(16.dp))
                }
                DropdownMenu(choosingType, { choosingType = false }) {
                    TransactionType.entries.forEach { type -> DropdownMenuItem(text = { Text(typeTitle(type)) }, onClick = {
                        choosingType = false
                        if (value.type != type) { search = ""; onChange(value.copy(type = type)) }
                    }, enabled = !saving) }
                }
            } else TransactionType.entries.forEach { type ->
                Column(Modifier.weight(1f).heightIn(min = 48.dp).selectable(value.type == type, enabled = !saving, role = Role.Tab) {
                    if (value.type != type) { search = ""; onChange(value.copy(type = type)) }
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(typeTitle(type), Modifier.padding(horizontal = 4.dp, vertical = 9.dp), style = MaterialTheme.typography.titleSmall,
                        color = if (value.type == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.width(24.dp).height(2.dp).background(if (value.type == type) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
            IconButton({ focus.clearFocus(); keyboardController?.hide(); searching = !searching; search = "" }, enabled = !saving && value.type != TransactionType.TRANSFER) {
                Icon(if (searching) LedgerIcons.Check else LedgerIcons.Search, appString(R.string.ui_search_categories))
            }
        }
        if (searching && value.type != TransactionType.TRANSFER) CompactEntryField(search, { search = it }, appString(R.string.ui_search_categories), Modifier.padding(horizontal = 12.dp), !saving)
        val catalogPane: @Composable (Modifier) -> Unit = { modifier ->
            if (value.type == TransactionType.TRANSFER) {
                Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EntryAccountRow(appString(R.string.ui_from_account), catalog.accounts.find { it.id == value.account }?.name ?: appString(R.string.ui_unassigned), !saving) { editor = "account" }
                    EntryAccountRow(appString(R.string.ui_to_account), catalog.accounts.find { it.id == value.destination }?.name ?: appString(R.string.ui_unassigned), !saving) { editor = "destination" }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompactEntryField(value.fee, { onChange(value.copy(fee = it)) }, appString(R.string.ui_fee_cny), Modifier.weight(1f), !saving, amount = true)
                        CompactEntryField(value.discount, { onChange(value.copy(discount = it)) }, appString(R.string.ui_discount_cny), Modifier.weight(1f), !saving, amount = true)
                    }
                    val received = runCatching { BigDecimal(java.math.BigInteger.valueOf(EntryAmount.evaluate(value.amount).minor) - java.math.BigInteger.valueOf(Money.parseCny(value.fee.ifBlank { "0" }).minor) + java.math.BigInteger.valueOf(Money.parseCny(value.discount.ifBlank { "0" }).minor), 2) }.getOrNull()
                    if (received != null) Text(appString(R.string.ui_received, received.toPlainString()), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                }
            } else EntryCategoryBoard( value.type, value.category, search, catalog, !saving, large, modifier) {
                onChange(value.copy(category = it))
                if (searching) { focus.clearFocus(); keyboardController?.hide(); searching = false; search = "" }
            }
        }
        val information: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                EntryAmountField(value.amount, { onChange(value.copy(amount = it)) }, amountLabel, !saving)
                EntryNoteField(value.note, { onChange(value.copy(note = it)) }, !saving, Modifier.focusRequester(noteFocus))
            }
            EntryTags(Modifier.padding(horizontal=8.dp)) {
                EntryOption(date.format(DateTimeFormatter.ofPattern(if (date.year == java.time.Year.now().value) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm")), appString(R.string.ui_choose_transaction_time), !saving) { editor = "time" }
                EntryOption(catalog.books.find { it.id == value.book }?.name.orEmpty(), appString(R.string.ui_ledger), !saving) { editor = "book" }
                if (value.type != TransactionType.TRANSFER) EntryOption(catalog.accounts.find { it.id == value.account }?.name ?: appString(R.string.ui_account), appString(R.string.ui_account), !saving) { editor = "account" }
                EntryOption(value.merchant.ifBlank { appString(R.string.ui_counterparty) }, appString(R.string.ui_counterparty), !saving) { editor = "details" }
                if (value.account.isNotEmpty()) EntryOption(if (value.affects) appString(R.string.ui_update_balance) else appString(R.string.ui_balance_unchanged_a5cbb), appString(R.string.ui_balance_policy), !saving,selector=false) { onChange(value.copy(affects = !value.affects)) }
            }
            error?.let { Text(localizedMessage(it), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            val footerLimit = maxHeight * if (large) 0.82f else 0.66f
            val controlWidth=maxOf(384.dp,maxWidth/2)
            val controls: @Composable (Modifier,Boolean) -> Unit = { modifier,split ->
                Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp)) {
                    if(split && !keyboard) Row(Modifier.fillMaxSize()) {
                        EntryInformation(Modifier.weight(1f).fillMaxHeight()) { information() }
                        EntryKeypad(saving,value.amount,{key->onChange(value.copy(amount=EntryAmount.edit(value.amount,key)))},onSave,onSaveAnother,
                            Modifier.width(192.dp).fillMaxHeight())
                    } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val informationLimit = (maxHeight - if (keyboard) 64.dp else 192.dp).coerceAtLeast(48.dp)
                        Column {
                            EntryInformation(Modifier.heightIn(max=informationLimit)) { information() }
                            if (!keyboard) EntryKeypad(saving, value.amount, { key -> onChange(value.copy(amount = EntryAmount.edit(value.amount, key))) }, onSave, onSaveAnother,
                                Modifier.weight(1f,fill=false))
                            else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton({ focus.clearFocus(); keyboardController?.hide() }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(appString(R.string.ui_hide_keypad)) }
                                Button(onSave, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !saving && value.amount.isNotBlank(),shape=LedgerShapes.action) { Text(appString(R.string.ui_save)) }
                            }
                        }
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                catalogPane(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(controlWidth).fillMaxHeight(),true)
            } else Column(Modifier.fillMaxSize()) {
                catalogPane(Modifier.weight(1f))
                controls(Modifier.fillMaxWidth().heightIn(max = footerLimit),false)
            }
        }
    }

    when (editor) {
        "book" -> EntryChoiceDialog(appString(R.string.ui_ledger), value.book, catalog.books.map { it.id to it.name }, { onChange(value.copy(book = it)); editor = null }, { editor = null })
        "account", "destination" -> {
            val destination = editor == "destination"
            val accounts = catalog.entryAccounts(value.account, value.destination).filter { !destination || it.id != value.account }
            EntryChoiceDialog(if (value.type == TransactionType.TRANSFER) if (destination) appString(R.string.ui_to_account) else appString(R.string.ui_from_account) else appString(R.string.ui_account),
                if (destination) value.destination else value.account, accounts.map { it.id to it.name }, {
                    onChange(if (destination) value.copy(destination = it) else value.copy(account = it, destination = value.destination.takeUnless { other -> other == it }.orEmpty()))
                    editor = null
                }, { editor = null })
        }
    }
}

/** A persistent scroll thumb exposes overflow in constrained editing windows. */
@Composable
private fun EntryInformation(modifier:Modifier=Modifier,content:@Composable ()->Unit) {
    val scroll=rememberScrollState()
    val thumb=MaterialTheme.colorScheme.outline
    Box(modifier.drawWithContent {
        drawContent()
        if(scroll.maxValue>0 && scroll.viewportSize>0) {
            val height=(size.height*scroll.viewportSize/(scroll.viewportSize.toFloat()+scroll.maxValue)).coerceAtLeast(16.dp.toPx()).coerceAtMost(size.height)
            val top=(size.height-height)*scroll.value/scroll.maxValue
            drawRoundRect(thumb,Offset(size.width-4.dp.toPx(),top),Size(2.dp.toPx(),height),CornerRadius(1.dp.toPx()))
        }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).semantics { contentDescription=appString(R.string.ui_entry_details) }) { content() }
    }
}

@Composable
private fun EntryOption(text: String, description: String, enabled: Boolean, selector:Boolean=true,onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; stateDescription=text }.padding(horizontal = 4.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        ControlScale(1.25f) {
            Row(Modifier.widthIn(max=280.dp).background(MaterialTheme.colorScheme.surfaceContainerLow,LedgerShapes.tag).padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,Modifier.weight(1f,fill=false),style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(selector)Icon(LedgerIcons.Down,null,Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun EntryAccountRow(title: String, name: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f));Text(name,color=MaterialTheme.colorScheme.primary);Icon(LedgerIcons.Down,null,Modifier.padding(start=8.dp).size(16.dp))
        Icon(LedgerIcons.Next, null, Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EntryCategoryBoard( type: TransactionType, selected: String, search: String, catalog: LedgerCatalog,
                               enabled: Boolean, large: Boolean, modifier: Modifier, onSelect: (String) -> Unit) {
    val options = catalog.categories.filter { it.type == type && (!it.hidden || it.id == selected) &&
        (it.displayName.contains(search, true) || it.name.contains(search, true)) }
    val display=categoryDisplay()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = categoryColumns(maxWidth, large, options.map { it.displayName },display)
        LazyColumn(Modifier.fillMaxSize().categoryLayoutMarker(display).semantics { contentDescription = appString(R.string.ui_category_list) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            items(options.chunked(columns), key = { it.first().id }) { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { category ->
                        val id = category.id; val checked = id == selected
                        Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = if(display==io.github.localledger.app.CategoryDisplay.LIST)48.dp else if (large) 72.dp else 58.dp)
                            .clip(LedgerShapes.selection)
                            .background(if(checked && display==io.github.localledger.app.CategoryDisplay.LIST)MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .selectable(checked, enabled = enabled, role = Role.RadioButton) { onSelect(id) }
                            .padding(horizontal = 2.dp, vertical = if(display==io.github.localledger.app.CategoryDisplay.LIST)0.dp else 4.dp),contentAlignment=Alignment.Center) {
                            CategoryItemContent(category.displayName,category.icon,checked,display)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (options.isEmpty()) item { Text(appString(R.string.ui_no_matching_categories), Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun EntryKeypad(saving: Boolean, amount: String, onKey: (String) -> Unit, onSave: () -> Unit, onSaveAnother: (() -> Unit)?, modifier: Modifier = Modifier) {
    ControlScale { EntryKeypadContent(saving,amount,onKey,onSave,onSaveAnother,modifier) }
}

@Composable
private fun EntryKeypadContent(saving: Boolean, amount: String, onKey: (String) -> Unit, onSave: () -> Unit, onSaveAnother: (() -> Unit)?, modifier: Modifier) {
    val rowHeight=48.dp
    val rows = listOf(listOf("1", "2", "3", "delete"), listOf("4", "5", "6", "-"), listOf("7", "8", "9", "+"),
        listOf(".", "0", if (onSaveAnother == null) "00" else "again", "save"))
    val keyRow: @Composable (List<String>) -> Unit = { row ->
        Row(Modifier.fillMaxWidth().height(rowHeight)) {
            row.forEach { key ->
                val save = key == "save"
                val valid = !saving && (key !in setOf("save", "again") || amount.isNotBlank())
                if (save) Box(Modifier.weight(1f).fillMaxHeight().clip(LedgerShapes.key).clickable(enabled=valid,role=Role.Button,onClick=onSave)
                    .semantics { contentDescription = appString(R.string.ui_save) }.padding(3.dp)
                    .background(if(valid || saving)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=.12f),LedgerShapes.key),contentAlignment=Alignment.Center) {
                    if(saving) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary)
                    else Text(appString(R.string.ui_save),style=MaterialTheme.typography.titleSmall,
                        color=if(valid)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                } else
                Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                    .clip(LedgerShapes.key)
                    .clickable(enabled = valid, role = Role.Button) { when (key) {
                        "again" -> onSaveAnother?.invoke(); else -> onKey(key)
                    } }.semantics { contentDescription = when (key) { "delete" -> appString(R.string.ui_delete_last_digit); "again" -> appString(R.string.ui_save_and_add_another); else -> appString(R.string.ui_enter, key) } }
                    .padding(3.dp).background(if(key in setOf("delete","+","-","again"))MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,LedgerShapes.key)
                    .padding(horizontal = 2.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    if (key == "delete") Icon(LedgerIcons.Backspace, null)
                    else Text(if (key == "again") appString(R.string.ui_add_another) else key,
                        style = if (key == "again") MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleLarge,
                        maxLines=1,color = if (!valid) MaterialTheme.colorScheme.onSurfaceVariant else if(key in setOf("+","-","again"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    Surface(modifier.fillMaxWidth().semantics { contentDescription=appString(R.string.ui_amount_keypad) },color=MaterialTheme.colorScheme.surfaceContainer) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val visibleRows=((maxHeight-rowHeight)/rowHeight).toInt().coerceIn(1,3)
        Column {
            Column(Modifier.height(rowHeight*visibleRows).verticalScroll(rememberScrollState())) { rows.take(3).forEach { keyRow(it) } }
            keyRow(rows.last())
        }
    }
    }
}

@Composable
private fun EntryChoiceDialog(title: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            if (options.size > 8) CompactEntryField(search, { search = it }, appString(R.string.ui_search, title))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(options.filter { it.second.contains(search, true) }, key = { it.first }) { (id, name) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(id == selected, role = Role.RadioButton) { onSelect(id) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(id == selected, null); Text(name, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text(appString(R.string.ui_cancel)) } })
}

@Composable
internal fun EntryDetailsEditor(value: EntryPageValues, onSave: (EntryPageValues) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf(value.note) }
    var merchant by rememberSaveable { mutableStateOf(value.merchant) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss = { focus.clearFocus(); keyboard?.hide(); onDismiss() }
    BackHandler(onBack = dismiss)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(dismiss) { Icon(LedgerIcons.Back, appString(R.string.ui_back)) }
            Text(appString(R.string.ui_note_and_counterparty), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(note, { note = it }, label = { Text(appString(R.string.ui_note)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(merchant, { merchant = it }, label = { Text(appString(R.string.ui_counterparty)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(dismiss, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(appString(R.string.ui_cancel)) }
            Button({ focus.clearFocus(); keyboard?.hide(); onSave(value.copy(note = note, merchant = merchant)) }, Modifier.weight(2f).heightIn(min = 48.dp)) { Text(appString(R.string.ui_ok)) }
        }
    }
}
