package io.github.localledger.app.ui
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString


import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.localledger.core.*
import io.github.localledger.app.R
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun typeTitle(type: TransactionType) = when (type) {
    TransactionType.EXPENSE -> appString(R.string.ui_expense); TransactionType.INCOME -> appString(R.string.ui_income); TransactionType.TRANSFER -> appString(R.string.ui_transfer)
}
@Composable
internal fun ChoiceField(title: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean = true,
                         quickChoices: Boolean = false,
                         compact: Boolean = false, state: String? = null,
                         onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val choicesView = remember { BringIntoViewRequester() }
    LaunchedEffect(open) { if (open) choicesView.bringIntoView() }
    val label = options.find { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    if (compact) Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectorSurface().clickable(enabled = enabled, role = Role.Button) { open = !open }
        .padding(horizontal=10.dp,vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state?.let { Text(" · $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Icon(LedgerIcons.Down, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectorSurface().clickable(enabled = enabled, role = Role.Button) { open = !open }
        .padding(horizontal=10.dp,vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Icon(LedgerIcons.Down, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!open && quickChoices && options.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 3) {
        options.filter { it.first.isNotEmpty() }.take(6).forEach { (id, name) ->
            FilterChip(selected == id, { onSelected(id) }, enabled = enabled,
                label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    if (open) Surface(modifier = Modifier.bringIntoViewRequester(choicesView), color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
        Column {
        if (options.size > 8) OutlinedTextField(search, { search = it }, label = { Text(appString(R.string.ui_search, title)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(options.filter { it.second.contains(search, true) }, key = { it.first }) { (id, name) -> Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(id == selected, enabled = enabled, role = Role.RadioButton) { onSelected(id); open = false; search = "" }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            ) { RadioButton(id == selected, null); Text(name, Modifier.padding(start = 8.dp)) } }
        }
        }
    }
}

@Composable
internal fun EntryForm(initial: EntryDraft, formKey: String, catalog: LedgerCatalog, saving: Boolean,
                       error: String?, allowTransfer: Boolean = true, title: String? = null,
                       newRecord: Boolean = false,
                       allowTimeEdit: Boolean = true,
                       inlineTimeEdit: Boolean = false,
                       confirmDiscard: Boolean = true,
                       memory: SelectionMemory = SelectionMemory(), status: String? = null,
                       compact: Boolean = false,
                       pageLayout: Boolean = false,
                       onSummaryChanged: ((TransactionType, String, String) -> Unit)? = null,
                       onFieldEditorChanged: (Boolean) -> Unit = {},
                       onSaveAnother: ((EntryDraft) -> Unit)? = null,
                       onSave: (EntryDraft) -> Unit, onCancel: () -> Unit) {
    var typeName by rememberSaveable(formKey) { mutableStateOf(initial.type.name) }
    val type = TransactionType.valueOf(typeName)
    var amount by rememberSaveable(formKey) { mutableStateOf(if (newRecord) "" else initial.amount.decimal().toPlainString()) }
    var merchant by rememberSaveable(formKey) { mutableStateOf(initial.merchant) }
    LaunchedEffect(type, amount, merchant) { onSummaryChanged?.invoke(type, amount, merchant) }
    var note by rememberSaveable(formKey) { mutableStateOf(initial.note) }
    var occurred by rememberSaveable(formKey) { mutableLongStateOf(initial.occurredAtMs) }
    var book by rememberSaveable(formKey) { mutableStateOf(initial.bookId) }
    var category by rememberSaveable(formKey) { mutableStateOf(initial.categoryId ?: io.github.localledger.app.CatalogLabels.fallback(catalog, initial.type).orEmpty()) }
    val initialAccount=initial.accountId ?: if(newRecord || compact)SelectionPolicy.resolve(catalog,memory,initial.type,initial.bookId).accountId else null
    var account by rememberSaveable(formKey) { mutableStateOf(initialAccount.orEmpty()) }
    var destination by rememberSaveable(formKey) { mutableStateOf(initial.toAccountId.orEmpty()) }
    var fee by rememberSaveable(formKey) { mutableStateOf(initial.fee.decimal().toPlainString()) }
    var discount by rememberSaveable(formKey) { mutableStateOf(initial.discount.decimal().toPlainString()) }
    var affects by rememberSaveable(formKey) { mutableStateOf(initial.affectsBalance) }
    var validation by rememberSaveable(formKey) { mutableStateOf<String?>(null) }
    var more by rememberSaveable(formKey) { mutableStateOf(false) }
    var editingTime by rememberSaveable(formKey) { mutableStateOf(false) }
    var categoriesInForm by rememberSaveable(formKey) { mutableStateOf<Map<String, String>>(hashMapOf()) }
    var accountsInForm by rememberSaveable(formKey) { mutableStateOf<Map<String, String>>(hashMapOf()) }
    var destinationsInForm by rememberSaveable(formKey) { mutableStateOf<Map<String, String>>(hashMapOf()) }
    var postingInForm by rememberSaveable(formKey) { mutableStateOf<Map<String, Boolean>>(hashMapOf()) }
    fun categoryKey(t: TransactionType) = t.name
    fun accountKey(t: TransactionType) = if (t == TransactionType.TRANSFER) "TRANSFER" else "ORDINARY"
    fun switchSelection(nextBook: String, nextType: TransactionType) {
        categoriesInForm = HashMap(categoriesInForm).apply { put(categoryKey(type), category) }
        accountsInForm = HashMap(accountsInForm).apply { put(accountKey(type), account) }
        if (type == TransactionType.TRANSFER) destinationsInForm = HashMap(destinationsInForm).apply { put("transfer", destination) }
        postingInForm = HashMap(postingInForm).apply { put(categoryKey(type), affects) }
        val choice = SelectionPolicy.resolve(catalog, memory, nextType, nextBook)
        category = categoriesInForm[categoryKey(nextType)] ?: choice.categoryId ?: io.github.localledger.app.CatalogLabels.fallback(catalog, nextType).orEmpty()
        account = accountsInForm[accountKey(nextType)] ?: choice.accountId.orEmpty()
        destination = destinationsInForm["transfer"] ?: choice.toAccountId.orEmpty()
        affects = if (nextType == type) affects else postingInForm[categoryKey(nextType)] ?: choice.affectsBalance
        book = nextBook; typeName = nextType.name; validation = null
    }
    val context = LocalContext.current
    val dialogTheme = if (LocalLedgerDark.current) R.style.LedgerDialog_Dark else R.style.LedgerDialog_Light
    var discard by remember { mutableStateOf(false) }
    val changed = type != initial.type || amount != (if (newRecord) "" else initial.amount.decimal().toPlainString()) ||
        merchant != initial.merchant || note != initial.note || occurred != initial.occurredAtMs || book != initial.bookId ||
        category != (initial.categoryId ?: io.github.localledger.app.CatalogLabels.fallback(catalog, initial.type).orEmpty()) || account != initialAccount.orEmpty() || destination != initial.toAccountId.orEmpty() ||
        fee != initial.fee.decimal().toPlainString() || discount != initial.discount.decimal().toPlainString() || affects != initial.affectsBalance
    val cancel = { if (confirmDiscard && changed) discard = true else onCancel() }
    if (confirmDiscard) BackHandler(enabled = !saving, onBack = cancel)
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(appString(R.string.ui_discard_these_changes)) },
        confirmButton = { TextButton({ discard = false; onCancel() }) { Text(appString(R.string.ui_discard)) } },
        dismissButton = { TextButton({ discard = false }) { Text(appString(R.string.ui_keep_editing)) } })
    val books = catalog.books.ifEmpty { listOf(LedgerBook(DEFAULT_BOOK, appString(R.string.ui_default_ledger))) }
    val accounts = catalog.entryAccounts(account, destination).map { it.id to it.name }
    if (editingTime) {
        EntryTimeEditor(occurred, { occurred = it; editingTime = false }, { editingTime = false })
        return
    }
    fun saveForm(another: Boolean = false) {
        try {
            require(account.isNotBlank()) { appString(R.string.ui_choose_account) }
            val save = if (another) onSaveAnother ?: onSave else onSave
            save(EntryDraft(type, if (pageLayout) EntryAmount.evaluate(amount) else Money.parseCny(amount), merchant.trim(), occurred, note, book,
                if (type == TransactionType.TRANSFER) null else category.takeIf(String::isNotEmpty) ?: io.github.localledger.app.CatalogLabels.fallback(catalog, type),
                account.takeIf(String::isNotEmpty), if (type == TransactionType.TRANSFER) destination.takeIf(String::isNotEmpty) else null,
                if (type == TransactionType.TRANSFER) Money.parseCny(fee.ifBlank { "0" }) else Money(0),
                if (type == TransactionType.TRANSFER) Money.parseCny(discount.ifBlank { "0" }) else Money(0), affects))
        } catch (e: IllegalArgumentException) { validation = e.message?.let(::localizedMessage) ?: appString(R.string.ui_check_the_entered_values) }
        catch (_: ArithmeticException) { validation = appString(R.string.ui_invalid_amount_precision_or_range) }
    }
    if (pageLayout) {
      key(formKey) {
        EntryPage(EntryPageValues(type, amount, merchant, note, occurred, book, category, account, destination, fee, discount, affects),
            catalog, saving, validation ?: error, onChange = { value ->
                validation = null
                if (value.book != book || value.type != type) switchSelection(value.book, value.type)
                else {
                    amount = value.amount; merchant = value.merchant; note = value.note; occurred = value.occurred
                    category = value.category; account = value.account; destination = value.destination
                    fee = value.fee; discount = value.discount; affects = value.affects
                }
            }, onSave = { saveForm() }, onSaveAnother = onSaveAnother?.let { { saveForm(true) } }, onCancel = cancel, focusNote = newRecord)
      }
        return
    }
    if (compact) {
        key(formKey) {
            CandidateFields(EntryPageValues(type, amount, merchant, note, occurred, book, category, account, destination, fee, discount, affects),
                catalog, saving, validation ?: error, status, onChange = { value ->
                    validation = null
                    if (value.book != book || value.type != type) switchSelection(value.book, value.type)
                    else { amount = value.amount; merchant = value.merchant; note = value.note; occurred = value.occurred
                        category = value.category; account = value.account; affects = value.affects }
                }, onFieldEditorChanged = onFieldEditorChanged, onSave = { saveForm() }, onCancel = cancel)
        }
        return
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
      Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
        val types: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TransactionType.entries.filter { allowTransfer || it != TransactionType.TRANSFER }.forEach { choice ->
                if (compact) Column(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
                    .selectable(type == choice, enabled = !saving, role = Role.Tab) { if (type != choice) switchSelection(book, choice) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(typeTitle(choice), Modifier.padding(horizontal = 8.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge,
                        color = if (type == choice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.width(24.dp).height(2.dp).background(if (type == choice) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
                } else FilterChip(type == choice, { if (type != choice) switchSelection(book, choice) },
                    label = { Text(typeTitle(choice)) }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (status != null) Text(status, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
        }
        }
        types()
        TextField(amount, { amount = it; validation = null }, label = { Text(if (type == TransactionType.TRANSFER) appString(R.string.ui_transfer_amount_cny) else appString(R.string.ui_amount_cny)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium), enabled = !saving,
            textStyle = MaterialTheme.typography.headlineLarge,
            prefix = { Text("¥", style = MaterialTheme.typography.titleLarge) },
            shape = MaterialTheme.shapes.medium,
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
        OutlinedTextField(merchant, { merchant = it; validation = null }, label = { Text(appString(R.string.ui_counterparty)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !saving)
        ChoiceField(appString(R.string.ui_ledger), book, books.map { it.id to it.name }, !saving) {
            if (it != book) switchSelection(it, type)
        }
        ChoiceField(if (type == TransactionType.TRANSFER) appString(R.string.ui_from_account) else appString(R.string.ui_account), account,
            accounts, !saving) { account = it }
        if (type != TransactionType.TRANSFER) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            CategoryPicker(type, category, catalog, !saving) { category = it }
        }
        if (type == TransactionType.TRANSFER) {
            ChoiceField(appString(R.string.ui_to_account), destination, accounts.filter { it.first != account }, !saving) { destination = it }
            OutlinedTextField(fee, { fee = it; validation = null }, label = { Text(appString(R.string.ui_fee_cny)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !saving)
            OutlinedTextField(discount, { discount = it; validation = null }, label = { Text(appString(R.string.ui_discount_cny)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !saving)
            val received = runCatching {
                BigInteger.valueOf(Money.parseCny(amount).minor) - BigInteger.valueOf(Money.parseCny(fee.ifBlank { "0" }).minor) +
                    BigInteger.valueOf(Money.parseCny(discount.ifBlank { "0" }).minor)
            }.getOrNull()
            if (received != null) Text(appString(R.string.ui_received, java.math.BigDecimal(received, 2).toPlainString()), style = MaterialTheme.typography.bodyMedium)
        }
        val date = Instant.ofEpochMilli(occurred).atZone(ZoneId.systemDefault())
        if (!compact || more) {
        if (allowTimeEdit) TextButton(onClick = {
            if (inlineTimeEdit) editingTime = true else
            DatePickerDialog(context, dialogTheme, { _, year, month, day ->
                TimePickerDialog(context, dialogTheme, { _, hour, minute ->
                    try { occurred = EntryTime.change(occurred, java.time.LocalDate.of(year, month + 1, day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), hour, minute, ZoneId.systemDefault()) }
                    catch (e: IllegalArgumentException) { validation = e.message }
                }, date.hour, date.minute, true).show()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, enabled = !saving, modifier = Modifier.fillMaxWidth().semantics { contentDescription = appString(R.string.ui_choose_transaction_time) }) { Text(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))) }
        else Text(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!compact) OutlinedTextField(note, { note = it; validation = null }, label = { Text(appString(R.string.ui_note)) },
            maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !saving)
        if (account.isNotEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(affects, enabled = !saving, role = Role.Switch) { affects = it }, verticalAlignment = Alignment.CenterVertically) {
            Text(appString(R.string.ui_include_in_account_balance), Modifier.weight(1f)); Switch(affects, null, enabled = !saving)
        }
        }
      }
      Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 2.dp) {
       Column(Modifier.padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 4.dp else 10.dp)) {
        (validation ?: error)?.let { Text(localizedMessage(it), Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(cancel, enabled = !saving, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(appString(R.string.ui_cancel)) }
            Button(onClick = { saveForm() }, enabled = !saving, modifier = Modifier.weight(2f).heightIn(min = 48.dp)) { Text(if (saving) appString(R.string.ui_saving) else appString(R.string.ui_save)) }
        }
       }
      }
    }
}
