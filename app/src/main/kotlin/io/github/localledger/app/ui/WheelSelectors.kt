package io.github.localledger.app.ui

import android.os.Build
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.localledger.app.R
import io.github.localledger.app.appString
import java.time.LocalDate
import java.time.YearMonth

/** Native wheel snapping and accessibility, shared by browsing and transaction time. */
@Composable
internal fun WheelSelector(label:String,value:Int,range:IntRange,tag:String,modifier:Modifier=Modifier,
                           padded:Boolean=false,wrap:Boolean=false,onChange:(Int)->Unit) {
    val change by rememberUpdatedState(onChange)
    val dark=LocalLedgerDark.current
    val color=MaterialTheme.colorScheme.onSurface.toArgb()
    ControlScale(1.25f) {
        val pixels=with(LocalDensity.current){22.sp.toPx()}
        Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
            Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(168.dp),contentAlignment=Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer,LedgerShapes.field))
                AndroidView(factory={base->
                    NumberPicker(ContextThemeWrapper(base,if(dark)R.style.Theme_LocalLedger_Dark else R.style.Theme_LocalLedger_Light)).apply {
                        descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        contentDescription=label
                        setOnValueChangedListener{_,_,next->change(next)}
                    }
                },modifier=Modifier.fillMaxSize().testTag(tag).semantics {
                    contentDescription=label;stateDescription=value.toString()
                    progressBarRangeInfo=ProgressBarRangeInfo(value.toFloat(),range.first.toFloat()..range.last.toFloat(),range.last-range.first-1)
                    setProgress { next->change(next.toInt().coerceIn(range));true }
                },update={wheel->
                    wheel.minValue=range.first;wheel.maxValue=range.last
                    wheel.wrapSelectorWheel=wrap
                    wheel.setFormatter{number->if(padded)number.toString().padStart(2,'0') else number.toString()}
                    wheel.value=value.coerceIn(range)
                    if(Build.VERSION.SDK_INT>=29) {
                        wheel.textColor=color;wheel.textSize=pixels;wheel.selectionDividerHeight=0
                    }
                })
            }
        }
    }
}

@Composable
internal fun MonthWheels(value:YearMonth,onChange:(YearMonth)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        WheelSelector(appString(R.string.ui_year),value.year,1..9999,"month-year",Modifier.weight(1.2f)){onChange(value.withYear(it))}
        WheelSelector(appString(R.string.ui_month),value.monthValue,1..12,"month-number",Modifier.weight(1f),padded=true,wrap=true){onChange(value.withMonth(it))}
    }
}

@Composable
internal fun DateWheels(value:LocalDate,onChange:(LocalDate)->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        fun changeMonth(year:Int,month:Int) {
            val next=YearMonth.of(year,month)
            onChange(next.atDay(value.dayOfMonth.coerceAtMost(next.lengthOfMonth())))
        }
        WheelSelector(appString(R.string.ui_year),value.year,1..9999,"date-year",Modifier.weight(1.2f)){changeMonth(it,value.monthValue)}
        WheelSelector(appString(R.string.ui_month),value.monthValue,1..12,"date-month",Modifier.weight(1f),padded=true,wrap=true){changeMonth(value.year,it)}
        WheelSelector(appString(R.string.ui_day),value.dayOfMonth,1..value.lengthOfMonth(),"date-day",Modifier.weight(1f),padded=true,wrap=true){onChange(value.withDayOfMonth(it))}
    }
}
