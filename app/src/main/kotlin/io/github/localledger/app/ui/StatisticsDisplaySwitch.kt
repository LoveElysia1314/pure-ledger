package io.github.localledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.localledger.app.R
import io.github.localledger.app.appString

/** Underlined tabs remain available for catalog and entry navigation. */
@Composable
internal fun LedgerTabs(labels:List<String>,selected:Int,onSelect:(Int)->Unit,modifier:Modifier=Modifier,enabled:(Int)->Boolean={true}) {
    ControlScale(1.25f) {
        Row(modifier.selectableGroup()) {
            labels.forEachIndexed { index,label ->
                val active=selected==index
                Column(Modifier.weight(1f).selectable(active,enabled=enabled(index),role=Role.Tab){onSelect(index)},horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=8.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
                        Text(label,style=MaterialTheme.typography.titleSmall,
                            fontWeight=if(active)FontWeight.SemiBold else FontWeight.Medium,
                            color=if(!enabled(index))MaterialTheme.colorScheme.onSurface.copy(alpha=.38f) else if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth(.45f).height(2.dp).background(if(active)MaterialTheme.colorScheme.primary else Color.Transparent,LedgerShapes.key))
                }
            }
        }
    }
}

@Composable
private fun StatisticsSegments(selected:Int,onSelect:(Int)->Unit,modifier:Modifier=Modifier,
                               enabled:(Int)->Boolean={true},content:@Composable (Int,Boolean)->Unit) {
    Box(modifier.heightIn(min=LedgerSpacing.touch)) {
        Box(Modifier.matchParentSize().padding(vertical=6.dp).clip(LedgerShapes.selection)
            .background(MaterialTheme.colorScheme.surfaceContainer))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            repeat(2) { index->
                val active=selected==index
                Box(Modifier.weight(1f).heightIn(min=LedgerSpacing.touch)
                    .selectable(active,enabled=enabled(index),role=Role.Tab){onSelect(index)},contentAlignment=Alignment.Center) {
                    Box(Modifier.matchParentSize().padding(horizontal=3.dp,vertical=8.dp).clip(LedgerShapes.key)
                        .background(if(active)MaterialTheme.colorScheme.primaryContainer else Color.Transparent))
                    Box(Modifier.padding(horizontal=8.dp,vertical=12.dp),contentAlignment=Alignment.Center) { content(index,active) }
                }
            }
        }
    }
}

@Composable
internal fun StatisticsDisplaySwitch(list:Boolean,onChange:(Boolean)->Unit,modifier:Modifier=Modifier) {
    StatisticsSegments(if(list)1 else 0,{onChange(it==1)},modifier.width(96.dp).testTag("statistics-display")) { index,selected->
        Icon(if(index==1)LedgerIcons.Statements else LedgerIcons.Statistics,
            appString(if(index==1)R.string.ui_list else R.string.ui_chart),Modifier.size(20.dp),
            tint=if(selected)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StatisticsControls(trend:Boolean,list:Boolean,categoryEnabled:Boolean,onTrend:(Boolean)->Unit,onList:(Boolean)->Unit) {
    ControlScale(1.25f) {
        Row(Modifier.fillMaxWidth().padding(horizontal=LedgerSpacing.page,vertical=LedgerSpacing.related),
            horizontalArrangement=Arrangement.spacedBy(LedgerSpacing.related),verticalAlignment=Alignment.CenterVertically) {
            StatisticsSegments(if(trend)1 else 0,{onTrend(it==1)},Modifier.weight(1f).testTag("statistics-dimensions"),
                enabled={it==1 || categoryEnabled}) { index,selected->
                Text(appString(if(index==0)R.string.ui_category_share else R.string.ui_cash_flow_change),
                    style=MaterialTheme.typography.labelLarge,
                    color=if(!categoryEnabled && index==0)MaterialTheme.colorScheme.onSurface.copy(alpha=.38f)
                        else if(selected)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatisticsDisplaySwitch(list,onList)
        }
    }
}
