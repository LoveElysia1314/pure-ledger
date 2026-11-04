package io.github.localledger.app.ui
import io.github.localledger.app.displayName
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.activity.compose.BackHandler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localledger.app.LedgerGraph
import io.github.localledger.app.DataPreview
import io.github.localledger.core.*
import java.math.BigDecimal
import java.math.RoundingMode

internal fun catalogTitle(kind: String) = when (kind) { "books" -> appString(R.string.ui_ledger); "accounts" -> appString(R.string.ui_account); "automatic" -> appString(R.string.ui_automatic_entries); "licenses" -> appString(R.string.ui_open_source_licenses); else -> appString(R.string.ui_category) }

@Composable
internal fun CatalogManager(kind: String, catalog: LedgerCatalog, graph: LedgerGraph, onDraftOpen: (Boolean) -> Unit = {}) {
    val snapshot by graph.snapshot.collectAsStateWithLifecycle()
    val query by graph.query.collectAsStateWithLifecycle()
    val busy by graph.busy.collectAsStateWithLifecycle()
    val error by graph.actionError.collectAsStateWithLifecycle()
    var editing by rememberSaveable(kind) { mutableStateOf(false) }
    var sorting by rememberSaveable(kind) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    fun edit(value: Boolean) { editing = value; onDraftOpen(value || sorting) }
    LaunchedEffect(editing, sorting) { onDraftOpen(editing || sorting) }
    DisposableEffect(Unit) { onDispose { onDraftOpen(false) } }
    var id by rememberSaveable(kind) { mutableStateOf<String?>(null) }
    var creationIncome by rememberSaveable(kind) { mutableStateOf(false) }
    var archivedOnly by rememberSaveable(kind) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var merging by rememberSaveable(kind) { mutableStateOf(false) }
    val managerState = rememberSaveableStateHolder()
    BackHandler(editing && !busy) { edit(false); graph.clearActionError() }
    if (merging && id != null) {
        BookMergeScreen(id!!, catalog, graph, { merging = false; graph.clearActionError() }, { merging = false; edit(false) })
        return
    }
    if (editing) {
        val selectedBook = catalog.books.find { it.id == id }
        val selectedAccount = catalog.accounts.find { it.id == id }
        val selectedCategory = catalog.categories.find { it.id == id }
        val key = "$kind:$id"
        var name by rememberSaveable(key) { mutableStateOf(selectedBook?.name ?: selectedAccount?.name ?: selectedCategory?.displayName.orEmpty()) }
        var defaultBook by rememberSaveable(key) { mutableStateOf(id != null && id == snapshot.selection.defaultBookId) }
        var income by rememberSaveable(key) { mutableStateOf(selectedCategory?.type == TransactionType.INCOME || selectedCategory == null && kind == "categories" && creationIncome) }
        var iconChoice by rememberSaveable(key) { mutableStateOf(selectedCategory?.icon) }
        var choosingIcon by rememberSaveable(key) { mutableStateOf(false) }
        var hidden by rememberSaveable(key) { mutableStateOf(selectedCategory?.hidden ?: false) }
        val initialBalance = rememberSaveable(key) { selectedAccount?.takeIf { it.balanceKnown }?.let { BigDecimal(it.balanceMinor, 2).toPlainString() }.orEmpty() }
        var balance by rememberSaveable(key) { mutableStateOf(initialBalance) }
        var validation by remember { mutableStateOf<String?>(null) }
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        if (kind == "categories" && choosingIcon) {
            BackHandler(!busy) { choosingIcon = false }
            Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.ui_icon_624ee, name.ifBlank { appString(R.string.ui_new_category) }), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton({ choosingIcon = false }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(appString(R.string.ui_cancel)) }
                }
                CategoryIconPicker(iconChoice ?: CategoryPresets.symbol(name), !busy,
                    { iconChoice = it; choosingIcon = false }, Modifier.weight(1f))
            }
        } else {
        Surface(Modifier.padding(12.dp).imePadding(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind == "categories") CompactEntryField(name, { name = it; validation = null }, appString(R.string.ui_name), enabled = !busy)
            else OutlinedTextField(name, { name = it; validation = null }, label = { Text(appString(R.string.ui_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (kind == "categories" && id != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                Text(typeTitle(if (income) TransactionType.INCOME else TransactionType.EXPENSE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (kind == "categories") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!income, { income = false }, label = { Text(appString(R.string.ui_expense)) }, enabled = !busy)
                    FilterChip(income, { income = true }, label = { Text(appString(R.string.ui_income)) }, enabled = !busy)
                }
            }
            if (kind == "accounts") OutlinedTextField(balance, { balance = it; validation = null }, label = { Text(appString(R.string.ui_current_balance_cny)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            if (kind == "books") Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(defaultBook, enabled = !busy, role = Role.Switch) { defaultBook = it }, verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.ui_use_as_default_ledger), Modifier.weight(1f)); Switch(defaultBook, null, enabled = !busy)
            }
            if (kind == "categories") {
                val icon = iconChoice ?: CategoryPresets.symbol(name)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(name, icon = icon)
                    Text(appString(R.string.ui_icon_0d720), Modifier.weight(1f).padding(start = 10.dp))
                    TextButton({ focus.clearFocus(); keyboard?.hide(); choosingIcon = true }, enabled = !busy) { Text(appString(R.string.ui_choose_c1133)) }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(Modifier.toggleable(hidden, enabled = !busy, role = Role.Switch) { hidden = it }), verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.ui_hide_category), Modifier.weight(1f)); Switch(hidden, null, enabled = !busy)
                }
            }
            (validation ?: error)?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ edit(false); graph.clearActionError() }, enabled = !busy) { Text(appString(R.string.ui_cancel)) }
                Button(onClick = {
                    try {
                        val done = { edit(false) }
                        when (kind) {
                            "books" -> graph.saveBook(name, id, done, defaultBook)
                            "categories" -> graph.saveCategoryStyle( if (income) TransactionType.INCOME else TransactionType.EXPENSE, if (selectedCategory != null && name == selectedCategory.displayName) selectedCategory.name else name, id,
                                iconChoice ?: CategoryPresets.symbol(name), hidden, done)
                            else -> {
                                val value = if (balance.isBlank() || id != null && balance == initialBalance) null else {
                                    require(balance.length <= 80 && Regex("[+-]?[0-9]+(?:\\.[0-9]+)?").matches(balance)) { appString(R.string.ui_invalid_balance_format) }
                                    BigDecimal(balance).setScale(2, RoundingMode.UNNECESSARY).unscaledValue()
                                }
                                graph.saveAccount(name, value, id, done)
                            }
                        }
                    } catch (e: IllegalArgumentException) { validation = e.message?.let(::localizedMessage) ?: appString(R.string.ui_check_the_entered_values) }
                    catch (_: ArithmeticException) { validation = appString(R.string.ui_balance_can_have_up_to_two_decimal_places) }
                }, enabled = !busy) { Text(appString(R.string.ui_save)) }
                if (kind == "accounts" && selectedAccount?.archived == true) TextButton({ graph.restoreAccount(selectedAccount.id) { edit(false) } }, enabled = !busy) { Text(appString(R.string.ui_restore_account)) }
                else if (id != null) TextButton({ confirmDelete = true }, enabled = !busy) { Text(appString(R.string.ui_delete)) }
            }
            if (kind == "books" && id != null && catalog.books.size > 1) TextButton({ focus.clearFocus(); keyboard?.hide(); graph.clearActionError(); merging = true }, enabled = !busy) { Text(appString(R.string.ui_merge_into_another_ledger)) }
        }
        }
        }
    } else if (kind == "categories") managerState.SaveableStateProvider("categories") { CategoryManager(catalog, busy, error,
        onEdit = { id = it; edit(true); graph.clearActionError() },
        onCreate = { selectedType -> id = null; creationIncome = selectedType == TransactionType.INCOME; edit(true); graph.clearActionError() },
        onArrange = graph::arrangeCategories,
        onHidden = graph::hideCategories, onRestore = graph::restoreCategories,
        onSortingChanged = { sorting = it; onDraftOpen(editing || it) }) }
    else LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(appString(R.string.ui_value, if (kind == "books") catalog.books.size else catalog.accounts.count { it.archived == archivedOnly }, catalogTitle(kind)), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (kind == "accounts" && (archivedOnly || catalog.accounts.any { it.archived })) TextButton({ archivedOnly = !archivedOnly; graph.clearActionError() }, enabled = !busy) { Text(if (archivedOnly) appString(R.string.ui_back_to_accounts) else appString(R.string.ui_deleted_04628, catalog.accounts.count { it.archived })) }
            if (!archivedOnly) TextButton({ id = null; edit(true); graph.clearActionError() }, enabled = !busy) { Text(appString(R.string.ui_add, catalogTitle(kind))) }
        } }
        item { error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) } }
        val rows = when (kind) {
            "books" -> catalog.books.map { Triple(it.id, it.name, "") }
            "categories" -> catalog.categories.map { Triple(it.id, it.displayName, typeTitle(it.type)) }
            else -> catalog.accounts.filter { it.archived == archivedOnly }.map { Triple(it.id, it.name,
                if (it.balanceKnown) "¥ ${BigDecimal(it.balanceMinor, 2).toPlainString()}" else appString(R.string.ui_balance_not_set)) }
        }
        items(rows, key = { it.first }) { value ->
            Surface(onClick = { id = value.first; edit(true); graph.clearActionError() }, enabled = !busy,
                color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
              Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value.second, style = MaterialTheme.typography.titleSmall)
                    if (value.third.isNotBlank()) Text(value.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (kind == "books") buildList {
                        if (value.first == query.bookId) add(appString(R.string.ui_current_ledger))
                        if (value.first == snapshot.selection.defaultBookId) add(appString(R.string.ui_default))
                    }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (kind == "books" && value.first != query.bookId) TextButton({ graph.setRememberedBook(value.first) }, enabled = !busy,
                    modifier = Modifier.padding(end = 8.dp)) { Text(appString(R.string.ui_use)) }
                else Icon(LedgerIcons.Next, null, Modifier.padding(end = 16.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(appString(R.string.ui_delete_50aa4, catalogTitle(kind))) },
        text = when {
            kind == "accounts" -> { { Text(appString(R.string.ui_remove_from_the_account_list_and_new_entry_choices), Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) } }
            kind == "books" && catalog.books.size == 1 -> { { Text(appString(R.string.ui_a_new_empty_ledger_will_be_created)) } }
            else -> null
        },
        confirmButton = { TextButton({ confirmDelete = false; id?.let { graph.deleteCatalog(kind, it) { edit(false) } } }) { Text(appString(R.string.ui_delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(appString(R.string.ui_cancel)) } })
}

@Composable
internal fun ImportPreviewScreen(preview: DataPreview, graph: LedgerGraph) {
    val busy by graph.busy.collectAsStateWithLifecycle()
    val error by graph.actionError.collectAsStateWithLifecycle()
    val snapshot by graph.snapshot.collectAsStateWithLifecycle()
    var affects by rememberSaveable(preview) { mutableStateOf(false) }
    var keep by rememberSaveable(preview) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val valid: Boolean
        when (preview) {
            is DataPreview.Legacy -> {
                val plan = preview.plan
                item { Text(appString(R.string.ui_entries_ledgers, plan.total, plan.books), style = MaterialTheme.typography.titleMedium) }
                item { Text(appString(R.string.ui_new_duplicate_conflict, plan.added, plan.skipped, plan.conflicts.size)) }
                item { Text(appString(R.string.ui_expense_098df, BigDecimal(plan.expense, 2).toPlainString())) }
                item { Text(appString(R.string.ui_income_transfers, BigDecimal(plan.income, 2).toPlainString(), plan.transfers)) }
                val rows = preview.document.rows
                items(rows.map { it.book }.distinct().sorted()) { name ->
                    val exists = snapshot.catalog.books.any { it.name == name }
                    Text(appString(R.string.ui_ledger_db6be, name, if (exists) appString(R.string.ui_reuse) else appString(R.string.ui_create)))
                }
                val accountNames = rows.flatMap { row ->
                    listOf(row.account, row.fromAccount, row.toAccount).filter(String::isNotBlank)
                }.distinct().sorted()
                items(accountNames) { name ->
                    val exists = snapshot.catalog.activeAccounts.any { it.name == name }
                    Text(appString(R.string.ui_value_fed2d, name, if (exists) appString(R.string.ui_reuse_account) else appString(R.string.ui_new_account)))
                }
                val retained = buildList {
                    rows.count { it.refund != 0L }.takeIf { it > 0 }?.let { add(appString(R.string.ui_refunds, it)) }
                    rows.count { it.needsReimbursement || it.reimbursement != 0L }.takeIf { it > 0 }?.let { add(appString(R.string.ui_reimbursements, it)) }
                    rows.count { it.image.isNotBlank() }.takeIf { it > 0 }?.let { add(appString(R.string.ui_image_references, it)) }
                    if (preview.document.unknownColumns.isNotEmpty()) add(preview.document.unknownColumns.sorted().joinToString("、"))
                }
                if (retained.isNotEmpty()) item { Text(appString(R.string.ui_kept_as_original_fields, retained.joinToString(" · ")), style = MaterialTheme.typography.bodySmall) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.ui_include_in_account_balance), Modifier.weight(1f)); Switch(affects, { affects = it }, enabled = !busy)
                } }
                if (plan.conflicts.isNotEmpty()) item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appString(R.string.ui_keep_both_conflicting_rows), Modifier.weight(1f)); Switch(keep, { keep = it }, enabled = !busy)
                } }
                items(plan.problems + plan.conflicts) { Text(appString(R.string.ui_row, it.sheet, it.row, localizedMessage(it.message)), color = MaterialTheme.colorScheme.error) }
                valid = plan.problems.isEmpty() && (keep || plan.conflicts.isEmpty())
            }
            is DataPreview.Exchange -> {
                item { Text(appString(R.string.ui_entries_1f405, preview.plan.total), style = MaterialTheme.typography.titleMedium) }
                item { Text(appString(R.string.ui_new_duplicate, preview.plan.added, preview.plan.skipped)) }
                items(preview.plan.conflicts) { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
                valid = preview.plan.conflicts.isEmpty()
            }
        }
        item { error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(graph::cancelPreview, enabled = !busy) { Text(appString(R.string.ui_cancel)) }
            Button({ graph.commitPreview(affects, keep) }, enabled = !busy && valid) { Text(if (busy) appString(R.string.ui_importing) else appString(R.string.ui_confirm_import)) }
        } }
    }
}
