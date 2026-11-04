package io.github.localledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import io.github.localledger.app.R
import io.github.localledger.app.appString
import io.github.localledger.app.displayName
import io.github.localledger.app.data.SummaryBucket
import io.github.localledger.core.TransactionType
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.atan2
import kotlin.math.hypot

internal data class CompositionSlice(val name: String?,val amount: BigInteger,val count: Int,val categories: Int=1)
internal fun categoryCountLabel(count:Int)=appString(if(count==1)R.string.ui_category_count_one else R.string.ui_category_count,count)

internal fun compositionSlices(buckets: List<SummaryBucket>,type: TransactionType): List<CompositionSlice> {
    val rows=buckets.filter { it.type==type }.sortedWith(compareByDescending<SummaryBucket>{it.amount}.thenBy{it.name})
    val keep=if(rows.size<=8)rows.size else 7
    return rows.take(keep).map { CompositionSlice(it.name,it.amount,it.count) }+
        if(keep==rows.size)emptyList() else listOf(CompositionSlice(null,rows.drop(keep).fold(BigInteger.ZERO){sum,row->sum+row.amount},rows.drop(keep).sumOf{it.count},rows.size-keep))
}

@Composable
internal fun CategoryShareChart(slices: List<CompositionSlice>,type: TransactionType,modifier: Modifier=Modifier,compact:Boolean=false,
                                onTypeChange:(()->Unit)?=null,onSlice:(CompositionSlice)->Unit) {
    val total=slices.fold(BigInteger.ZERO){sum,slice->sum+slice.amount}
    val colors=statisticsColors(LocalLedgerDark.current)
    val track=MaterialTheme.colorScheme.surfaceContainerHighest
    val diameterDp=if(compact)168.dp else 216.dp
    val outside=LocalDensity.current.fontScale>1.3f || statementAmount(total).length>11
    Column(modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
    Box(Modifier.fillMaxWidth().height(diameterDp+8.dp),contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(diameterDp).semantics { contentDescription=appString(R.string.ui_category_share) }
            .pointerInput(slices,total){detectTapGestures { point->
                if(total.signum()>0) {
                    val center=Offset(size.width/2f,size.height/2f);val radius=hypot(point.x-center.x,point.y-center.y)
                    if(radius in size.width*.29f..size.width*.5f) {
                        val angle=((Math.toDegrees(atan2((point.y-center.y).toDouble(),(point.x-center.x).toDouble()))+450)%360).toFloat()
                        var end=0f
                        slices.firstOrNull { end+=BigDecimal(it.amount).divide(BigDecimal(total),12,RoundingMode.HALF_UP).toFloat()*360f;angle<end }?.let(onSlice)
                    }
                }
            }}) {
            val stroke=(if(compact)14.dp else 18.dp).toPx();val inset=stroke/2+4.dp.toPx();val diameter=size.width-2*inset
            drawCircle(track,radius=diameter/2,style=Stroke(stroke))
            if(total.signum()>0) {
                var start=-90f
                slices.forEachIndexed { index,slice->
                    val sweep=BigDecimal(slice.amount).divide(BigDecimal(total),12,RoundingMode.HALF_UP).toFloat()*360f
                    drawArc(colors[index%colors.size],start+1f,(sweep-2f).coerceAtLeast(0f),false,Offset(inset,inset),Size(diameter,diameter),style=Stroke(stroke))
                    start+=sweep
                }
            }
        }
        Column(Modifier.width(diameterDp-60.dp).heightIn(min=LedgerSpacing.touch)
            .then(if(onTypeChange!=null)Modifier.clickable(role=Role.Button,onClickLabel=typeTitle(if(type==TransactionType.EXPENSE)TransactionType.INCOME else TransactionType.EXPENSE),onClick=onTypeChange)
                .testTag("statistics-type-toggle") else Modifier),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp,Alignment.CenterVertically)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(typeTitle(type),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(onTypeChange!=null)Icon(LedgerIcons.Swap,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
            }
            if(!outside)Text(statementAmount(total),style=MaterialTheme.typography.titleSmall)
            if(!outside)Text(categoryCountLabel(slices.sumOf{it.categories}),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
        if(outside) {
            Text(statementAmount(total),Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.titleMedium)
            Text(categoryCountLabel(slices.sumOf{it.categories}),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CompositionOverview(slices: List<CompositionSlice>,type: TransactionType,catalog: io.github.localledger.core.LedgerCatalog,
                                 onTypeChange:(()->Unit)?=null,onSlice:(CompositionSlice)->Unit) {
    val colors=statisticsColors(LocalLedgerDark.current)
    val total=slices.fold(BigInteger.ZERO){sum,slice->sum+slice.amount}
    val legend: @Composable (Int,CompositionSlice,Boolean)->Unit={index,slice,compact->
        val name=slice.name ?: appString(R.string.ui_remaining_categories,slice.categories)
        val icon=catalog.categories.firstOrNull { it.type==type && it.displayName==name }?.icon
        CategoryStatistic(name,slice.amount,total,slice.count,icon,colors[index%colors.size],slice.name==null,compact){onSlice(slice)}
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
        if(maxWidth>=720.dp)Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
            CategoryShareChart(slices,type,Modifier.width(260.dp),onTypeChange=onTypeChange,onSlice=onSlice)
            Box(Modifier.weight(1f)) { AdaptiveItems(slices,280.dp){index,slice->legend(index,slice,false)} }
        } else Column {
            CategoryShareChart(slices,type,compact=true,onTypeChange=onTypeChange,onSlice=onSlice)
            slices.indices.sortedByDescending{slices[it].amount}.forEach { index->legend(index,slices[index],true) }
        }
    }
}
