package io.github.localledger.app.ui
import io.github.localledger.app.displayName
import io.github.localledger.app.localizedMessage
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.localledger.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LedgerFilters(query: LedgerQuery, catalog: LedgerCatalog, onApply: (LedgerQuery) -> Unit) {
    var text by rememberSaveable { mutableStateOf(query.text) }
    var book by rememberSaveable { mutableStateOf(query.bookId.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(query.type?.name.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(query.categoryId.orEmpty()) }
    var account by rememberSaveable { mutableStateOf(query.accountId.orEmpty()) }
    var from by rememberSaveable { mutableStateOf(query.firstDate()?.toString().orEmpty()) }
    var until by rememberSaveable { mutableStateOf(query.lastDate()?.toString().orEmpty()) }
    var minimum by rememberSaveable { mutableStateOf(query.minimumMinor?.let { Money(it).decimal().toPlainString() }.orEmpty()) }
    var maximum by rememberSaveable { mutableStateOf(query.maximumMinor?.let { Money(it).decimal().toPlainString() }.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var monthChooser by rememberSaveable { mutableStateOf(false) }
    fun dateQuery() = LedgerQuery().inDateRange(from.takeIf(String::isNotBlank)?.let(LocalDate::parse), until.takeIf(String::isNotBlank)?.let(LocalDate::parse))
    fun choosePeriod(period: LedgerQuery) { from = period.firstDate()?.toString().orEmpty(); until = period.lastDate()?.toString().orEmpty(); error = null }
    val period = runCatching { dateQuery() }.getOrNull()
    var rangeChooser by rememberSaveable{mutableStateOf(false)}
    if(monthChooser)MonthChooser(period ?: LedgerQuery(),{choosePeriod(it);monthChooser=false},{monthChooser=false},onCustom={monthChooser=false;rangeChooser=true})
    if(rangeChooser)PeriodRangeEditor(period ?: LedgerQuery(),{choosePeriod(it);rangeChooser=false},{rangeChooser=false})
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(text, { text = it; error = null }, label = { Text(appString(R.string.ui_search_merchant_note_category_or_account)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                trailingIcon = if (text.isNotEmpty()) { { IconButton({ text = "" }) { Icon(LedgerIcons.Close, appString(R.string.ui_clear_search)) } } } else null)
            FilterGroup {
                ChoiceField(appString(R.string.ui_ledger), book, listOf("" to appString(R.string.ui_all_ledgers)) + catalog.books.map { it.id to it.name }) { book = it }
                Row(Modifier.fillMaxWidth()) {
                    (listOf("" to appString(R.string.ui_all)) + TransactionType.entries.map { it.name to typeTitle(it) }).forEach { (name, label) ->
                        Column(Modifier.weight(1f).heightIn(min = 48.dp).selectable(kind == name, role = Role.Tab) { kind = name; category = "" },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(label, Modifier.padding(vertical = 8.dp), color = if (kind == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (kind == name) HorizontalDivider(Modifier.width(24.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                ChoiceField(appString(R.string.ui_category), category, listOf("" to appString(R.string.ui_all_categories)) + catalog.categories.filter {
                    (kind.isEmpty() || it.type.name == kind)
                }.map { it.id to buildList { add(it.displayName); if (kind.isEmpty()) add(typeTitle(it.type)) }.joinToString(" · ") }) { category = it }
                ChoiceField(appString(R.string.ui_account), account, listOf("" to appString(R.string.ui_all_accounts)) + catalog.accounts.map { it.id to (it.name + if (it.archived) appString(R.string.ui_deleted) else "") }) { account = it }
            }
            FilterGroup {
                Text(appString(R.string.ui_time_range),Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                SelectorButton(period?.periodTitle() ?: appString(R.string.ui_month_range),appString(R.string.ui_choose_date_range),{monthChooser=true},Modifier.fillMaxWidth())
            }
            FilterGroup {
                Text(appString(R.string.ui_amount_cny), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactEntryField(minimum, { minimum = it; error = null }, appString(R.string.ui_minimum_amount), Modifier.weight(1f), amount = true)
                    CompactEntryField(maximum, { maximum = it; error = null }, appString(R.string.ui_maximum_amount), Modifier.weight(1f), amount = true)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
          Column {
            error?.let { Text(localizedMessage(it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ text = ""; kind = ""; category = ""; account = ""; minimum = ""; maximum = ""; error = null }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(appString(R.string.ui_reset)) }
                Button({
                    try {
                        val bounds = dateQuery()
                        onApply(LedgerQuery(text.trim(), book.takeIf(String::isNotEmpty), kind.takeIf(String::isNotEmpty)?.let(TransactionType::valueOf),
                            category.takeIf(String::isNotEmpty), account.takeIf(String::isNotEmpty), bounds.fromMs, bounds.untilMs,
                            minimum.takeIf(String::isNotBlank)?.let { Money.parseCny(it).minor }, maximum.takeIf(String::isNotBlank)?.let { Money.parseCny(it).minor }))
                    } catch (e: IllegalArgumentException) { error = e.message?.let(::localizedMessage) ?: appString(R.string.ui_could_not_apply_the_filters_adjust_the_values) }
                }, Modifier.weight(2f).heightIn(min = 48.dp)) { Text(appString(R.string.ui_apply)) }
            }
          }
        }
    }
}

@Composable
private fun FilterGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), content = content)
    }
}
