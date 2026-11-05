package io.github.localledger.app.ui
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.localledger.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A single overlay window: secondary fields replace its content, never steal its window focus. */
@Composable
internal fun CandidateFields(value: EntryPageValues, catalog: LedgerCatalog, saving: Boolean, error: String?, status: String?,
                             onChange: (EntryPageValues) -> Unit, onFieldEditorChanged: (Boolean) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(editor) { onFieldEditorChanged(editor != null) }
    DisposableEffect(Unit) { onDispose { onFieldEditorChanged(false) } }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finishEditor = { focus.clearFocus(); keyboard?.hide(); editor = null }
    BackHandler(editor != null, finishEditor)
    when (editor) {
        "time" -> { EntryTimeEditor(value.occurred, { onChange(value.copy(occurred = it)); finishEditor() }, finishEditor); return }
        "details" -> { EntryDetailsEditor(value, { onChange(it); finishEditor() }, finishEditor); return }
        "book", "account" -> {
            val account = editor == "account"
            CandidateChoiceEditor(if (account) appString(R.string.ui_account) else appString(R.string.ui_ledger), if (account) value.account else value.book,
                if (account) catalog.entryAccounts(value.account).map { it.id to it.name } else catalog.books.map { it.id to it.name },
                if (account) value.affects else null, saving, { id, affects ->
                    onChange(if (account) value.copy(account = id, affects = affects ?: value.affects) else value.copy(book = id)); finishEditor()
                }, finishEditor)
            return
        }
    }
    val large = LocalDensity.current.fontScale > 1.3f
    val date = Instant.ofEpochMilli(value.occurred).atZone(ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEach { type ->
                    Column(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).selectable(type == value.type, enabled = !saving, role = Role.Tab) {
                        onChange(value.copy(type = type))
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(typeTitle(type), Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                            color = if (type == value.type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (type == value.type) HorizontalDivider(Modifier.width(24.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (status != null && !(large && status.length > 3)) DuplicateBadge(status, Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                IconButton({ focus.clearFocus(); keyboard?.hide(); searching = !searching; search = "" }, modifier = Modifier.size(48.dp), enabled = !saving) {
                    Icon(if (searching) LedgerIcons.Check else LedgerIcons.Search, if (searching) appString(R.string.ui_finish_category_search) else appString(R.string.ui_search_categories))
                }
            }
            if (status != null && large && status.length > 3) DuplicateBadge(status, Modifier.padding(vertical = 4.dp))
            EntryAmountField(value.amount, { onChange(value.copy(amount = it)) }, appString(R.string.ui_amount_cny), enabled = !saving,systemKeyboard=true)
            EntryNoteField(value.note, { onChange(value.copy(note = it)) }, !saving)
            if (searching) CompactEntryField(search, { search = it }, appString(R.string.ui_search_categories), enabled = !saving)
            EntryCategoryBoard( value.type, value.category, search, catalog, !saving, large,
                Modifier.height(if (LocalConfiguration.current.screenHeightDp < 500) { if (large) 160.dp else 128.dp } else { if (large) 180.dp else 256.dp })) {
                onChange(value.copy(category = it))
                if (searching) { focus.clearFocus(); keyboard?.hide(); searching = false; search = "" }
            }
        }
            EntryTags(Modifier.padding(horizontal=12.dp)) {
                CandidateTag(date.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), appString(R.string.ui_choose_transaction_time), !saving,
                    date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))) { focus.clearFocus(); keyboard?.hide(); editor = "time" }
                val account = catalog.accounts.find { it.id == value.account }?.name ?: appString(R.string.ui_account)
                CandidateTag(if (value.account.isNotEmpty() && !value.affects) appString(R.string.ui_balance_unchanged, account) else account, appString(R.string.ui_account), !saving) { focus.clearFocus(); keyboard?.hide(); editor = "account" }
                CandidateTag(catalog.books.find { it.id == value.book }?.name ?: appString(R.string.ui_ledger), appString(R.string.ui_ledger), !saving) { focus.clearFocus(); keyboard?.hide(); editor = "book" }
                CandidateTag(value.merchant.ifBlank { appString(R.string.ui_counterparty) }, appString(R.string.ui_counterparty), !saving, value.merchant) { focus.clearFocus(); keyboard?.hide(); editor = "details" }
            }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ focus.clearFocus(); keyboard?.hide(); onCancel() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !saving) { Text(appString(R.string.ui_cancel)) }
                    Button({ focus.clearFocus(); keyboard?.hide(); onSave() }, Modifier.weight(2f).heightIn(min = 48.dp), enabled = !saving,shape=LedgerShapes.action) { Text(if (saving) appString(R.string.ui_saving) else appString(R.string.ui_save)) }
                }
            }
        }
    }
}

@Composable
private fun CandidateTag(text: String, description: String, enabled: Boolean, detail: String = text, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; stateDescription = detail }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        ControlScale(1.25f) {
            Row(Modifier.widthIn(max=280.dp).background(MaterialTheme.colorScheme.surfaceContainer,LedgerShapes.tag).padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(text,Modifier.weight(1f,fill=false),style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=TextOverflow.Ellipsis);Icon(LedgerIcons.Down,null,Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun CandidateChoiceEditor(title: String, selected: String, options: List<Pair<String, String>>, balance: Boolean?, saving: Boolean,
                                  onSelect: (String, Boolean?) -> Unit, onCancel: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var affects by rememberSaveable { mutableStateOf(balance ?: true) }
    Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(horizontal = 12.dp)) {
        Text(title, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        if (options.size > 8) CompactEntryField(search, { search = it }, appString(R.string.ui_search, title), enabled = !saving)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(options.filter { it.second.contains(search, true) }, key = { it.first }) { (id, name) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(id == selected, enabled = !saving, role = Role.RadioButton) {
                    onSelect(id, if (balance == null) null else affects)
                }, verticalAlignment = Alignment.CenterVertically) { RadioButton(id == selected, null); Text(name, Modifier.padding(start = 8.dp)) }
            }
        }
        if (balance != null) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(affects, enabled = !saving, role = Role.Switch) { affects = it }, verticalAlignment = Alignment.CenterVertically) {
            Text(appString(R.string.ui_include_in_account_balance), Modifier.weight(1f)); Switch(affects, null, enabled = !saving)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onCancel, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !saving) { Text(appString(R.string.ui_cancel)) }
            if (balance != null) Button({ onSelect(selected, affects) }, Modifier.weight(2f).heightIn(min = 48.dp), enabled = !saving) { Text(appString(R.string.ui_ok)) }
        }
    }
}
