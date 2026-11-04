package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.localledger.app.Appearance
import io.github.localledger.core.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal fun LedgerQuery.month(): YearMonth? = calendarMonth()
internal fun LedgerQuery.periodTitle(): String {
    val now=YearMonth.now()
    calendarMonth()?.let { return when(it){now->appString(R.string.ui_this_month);now.minusMonths(1)->appString(R.string.ui_last_month);else->it.toString()} }
    calendarYear()?.let{return if(it==now.year)appString(R.string.ui_this_year) else it.toString()}
    val first=firstDate();val last=lastDate()
    return when {
        first==null && last==null->appString(R.string.ui_all_time)
        first==null->appString(R.string.ui_until,YearMonth.from(last))
        last==null->appString(R.string.ui_since,YearMonth.from(first))
        first.dayOfMonth==1 && last==YearMonth.from(last).atEndOfMonth()->"${YearMonth.from(first)} – ${YearMonth.from(last)}"
        else->appString(R.string.ui_to,first,last)
    }
}
internal fun LedgerQuery.withMonth(month: YearMonth?): LedgerQuery {
    return inMonth(month)
}

@Composable
internal fun MonthNavigation(query:LedgerQuery,enabled:Boolean,onQuery:(LedgerQuery)->Unit,onChoose:()->Unit,modifier:Modifier=Modifier) {
    val previous=runCatching{query.shiftBrowsePeriod(-1)}.getOrNull()
    val next=runCatching{query.shiftBrowsePeriod(1)}.getOrNull()
    val month=query.month()!=null || query.fromMs==null && query.untilMs==null
    Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        IconButton({previous?.let(onQuery)},enabled=enabled && previous!=null,modifier=Modifier.semantics{contentDescription=appString(if(month)R.string.ui_previous_month else R.string.ui_previous_period)}){Icon(LedgerIcons.Previous,null)}
        SelectorButton(query.periodTitle(),appString(R.string.ui_choose_date_range),onChoose,Modifier.weight(1f),enabled,period=true)
        IconButton({next?.let(onQuery)},enabled=enabled && next!=null,modifier=Modifier.semantics{contentDescription=appString(if(month)R.string.ui_next_month else R.string.ui_next_period)}){Icon(LedgerIcons.Next,null)}
    }
}

internal val browsePresets=listOf(DateRangePreset.THIS_MONTH,DateRangePreset.LAST_MONTH,DateRangePreset.THIS_YEAR,DateRangePreset.ALL)

@Composable
internal fun MonthChooser(query:LedgerQuery,onSelect:(LedgerQuery)->Unit,onDismiss:()->Unit,onCustom:(()->Unit)?=null) {
    val initial=query.month() ?: query.firstDate()?.let(YearMonth::from) ?: YearMonth.now()
    var month by rememberSaveable{mutableStateOf(initial.toString())}
    AlertDialog(onDismissRequest=onDismiss,title={Text(appString(R.string.ui_choose_date_range))},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val choices=browsePresets.map { preset->
                val value=query.inPreset(preset)
                Triple(preset.title(),query.fromMs==value.fromMs && query.untilMs==value.untilMs,{onSelect(value)})
            } + if(onCustom==null)emptyList() else listOf(Triple(appString(R.string.ui_month_range),false,onCustom))
            choices.chunked(2).forEach { row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { (name,selected,action)->FilterChip(selected,action,label={Text(name)},modifier=Modifier.weight(1f).heightIn(min=48.dp)) }
                repeat(2-row.size){Spacer(Modifier.weight(1f))}
            } }
            MonthWheels(YearMonth.parse(month)){month=it.toString()}
            TextButton({onSelect(query.inYear(YearMonth.parse(month).year))},Modifier.heightIn(min=48.dp)){Text(appString(R.string.ui_whole_year))}
        }
    },confirmButton={TextButton({onSelect(query.inMonth(YearMonth.parse(month)))}){Text(appString(R.string.ui_apply))}},
        dismissButton={TextButton(onDismiss){Text(appString(R.string.ui_cancel))}})
}

@Composable
internal fun MonthOnlyPicker(current:YearMonth,onSelect:(YearMonth)->Unit,onDismiss:()->Unit) {
    var month by rememberSaveable(current){mutableStateOf(current.toString())}
    AlertDialog(onDismissRequest=onDismiss,title={Text(appString(R.string.ui_choose_month))},text={
        MonthWheels(YearMonth.parse(month)){month=it.toString()}
    },confirmButton={TextButton({onSelect(YearMonth.parse(month))}){Text(appString(R.string.ui_ok))}},
        dismissButton={TextButton(onDismiss){Text(appString(R.string.ui_cancel))}})
}

@Composable
internal fun BookChooser(catalog: LedgerCatalog, selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.ui_choose_ledger)) }, text = {
        Column {
            if (catalog.books.size > 8) OutlinedTextField(search, { search = it }, label = { Text(appString(R.string.ui_search_ledgers)) }, singleLine = true)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                val options = listOf(null to appString(R.string.ui_all_ledgers)) + catalog.books.map { it.id to it.name }
                items(options.filter { it.second.contains(search, true) }, key = { it.first ?: "all" }) { (id, name) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onSelect(id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(id == selected, null); Text(name, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text(appString(R.string.ui_cancel)) } })
}

@Composable
internal fun AppearanceChooser(selected: Appearance, onSelect: (Appearance) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(appString(R.string.ui_appearance)) }, text = {
        Column { Appearance.entries.forEach { value ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onSelect(value) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(value == selected, null); Text(value.title, Modifier.padding(start = 8.dp))
            }
        } }
    }, confirmButton = { TextButton(onDismiss) { Text(appString(R.string.ui_cancel)) } })
}
