package io.github.localledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.app.localizedMessage
import io.github.localledger.core.EntryTime
import java.time.*

/** Temporary wheel selection in the current window; only Done changes the draft time. */
@Composable
internal fun EntryTimeEditor(value:Long,onDone:(Long)->Unit,onCancel:()->Unit) {
    val zone=ZoneId.systemDefault()
    val original=remember(value){Instant.ofEpochMilli(value).atZone(zone)}
    var date by rememberSaveable(value){mutableStateOf(original.toLocalDate().toString())}
    var hour by rememberSaveable(value){mutableStateOf(original.hour.toString().padStart(2,'0'))}
    var minute by rememberSaveable(value){mutableStateOf(original.minute.toString().padStart(2,'0'))}
    var section by rememberSaveable(value){mutableIntStateOf(0)}
    var dateInput by rememberSaveable(value){mutableStateOf(false)}
    var timeInput by rememberSaveable(value){mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val cancel={focus.clearFocus();keyboard?.hide();onCancel()}
    BackHandler(onBack=cancel)
    Column(Modifier.fillMaxWidth().heightIn(max=500.dp).semantics{paneTitle=appString(R.string.ui_transaction_time)}) {
        Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                LedgerTabs(listOf(appString(R.string.ui_date),appString(R.string.ui_time)),section,{
                    focus.clearFocus();keyboard?.hide();section=it;error=null
                },Modifier.weight(1f))
                TextButton({
                    focus.clearFocus();keyboard?.hide();error=null
                    if(section==0) {
                        if(!dateInput || runCatching{LocalDate.parse(date)}.isSuccess)dateInput=!dateInput
                        else error=appString(R.string.ui_invalid_date)
                    } else {
                        if(!timeInput || hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59)timeInput=!timeInput
                        else error=appString(R.string.ui_invalid_time)
                    }
                },Modifier.heightIn(min=48.dp).semantics{contentDescription=appString(if(section==0)R.string.ui_switch_date_input else R.string.ui_switch_time_input)}) {
                    Text(appString(if(if(section==0)dateInput else timeInput)R.string.ui_wheel else R.string.ui_input))
                }
            }
            if(section==0) {
                if(dateInput)CompactEntryField(date,{date=it;error=null},appString(R.string.ui_date),Modifier.padding(horizontal=12.dp))
                else DateWheels(runCatching{LocalDate.parse(date)}.getOrDefault(original.toLocalDate())){date=it.toString();error=null}
            } else if(timeInput)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                CompactEntryField(hour,{hour=it;error=null},appString(R.string.ui_hour),Modifier.weight(1f),amount=true,keyboardType=KeyboardType.Number)
                CompactEntryField(minute,{minute=it;error=null},appString(R.string.ui_minute),Modifier.weight(1f),amount=true,keyboardType=KeyboardType.Number)
            } else Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                WheelSelector(appString(R.string.ui_hour),hour.toIntOrNull()?.coerceIn(0,23) ?: original.hour,0..23,"time-hour",Modifier.weight(1f),padded=true,wrap=true){hour=it.toString().padStart(2,'0');error=null}
                WheelSelector(appString(R.string.ui_minute),minute.toIntOrNull()?.coerceIn(0,59) ?: original.minute,0..59,"time-minute",Modifier.weight(1f),padded=true,wrap=true){minute=it.toString().padStart(2,'0');error=null}
            }
            error?.let{Text(localizedMessage(it),Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.error)}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(cancel,Modifier.weight(1f).heightIn(min=48.dp)){Text(appString(R.string.ui_cancel))}
            Button({
                try {
                    val selected=LocalDate.parse(date)
                    require(selected.year in 1..9999){appString(R.string.ui_invalid_date)}
                    val h=hour.toIntOrNull();val m=minute.toIntOrNull()
                    require(h!=null && h in 0..23){appString(R.string.ui_hour_must_be_0_23)}
                    require(m!=null && m in 0..59){appString(R.string.ui_minute_must_be_0_59)}
                    val next=EntryTime.change(value,selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),h,m,zone)
                    focus.clearFocus();keyboard?.hide();onDone(next)
                } catch(e:IllegalArgumentException){error=e.message}
                catch(_:DateTimeException){error=appString(R.string.ui_invalid_date)}
            },Modifier.weight(2f).heightIn(min=48.dp)){Text(appString(R.string.ui_done_c0b3f))}
        }
    }
}
