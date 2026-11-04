package io.github.localledger.app.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import io.github.localledger.app.CategoryDisplay
import io.github.localledger.app.AppStrings

/** Allocate wider cells when the displayed vocabulary contains Latin words. */
internal fun categoryColumns(width: Dp, large: Boolean, names: List<String>, display: CategoryDisplay=CategoryDisplay.GRID): Int {
    if(display==CategoryDisplay.LIST)return ((width-16.dp)/(if(large)144.dp else 104.dp)).toInt().coerceIn(1,6)
    val latin = names.any { name -> name.any { it in 'A'..'Z' || it in 'a'..'z' } }
    val cell = if (large) { if (latin) 96.dp else 76.dp } else if (latin) 84.dp else 60.dp
    return ((width - 16.dp) / cell).toInt().coerceIn(1, 5)
}

@Composable
internal fun categoryDisplay()=LocalCategoryDisplay.current.resolve(AppStrings.locale.language)

internal fun Modifier.categoryLayoutMarker(display: CategoryDisplay)=testTag("category-layout-${display.name.lowercase(java.util.Locale.ROOT)}")

@Composable
internal fun CategoryItemContent(name: String,icon: String,selected: Boolean,display: CategoryDisplay,modifier: Modifier=Modifier) {
    if(display==CategoryDisplay.LIST)ControlScale(1.25f) {
        Row(modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Icon(CategoryGlyphs[icon],null,Modifier.size(22.dp),tint=if(selected)MaterialTheme.colorScheme.primary else categoryTint(icon,LocalLedgerDark.current))
            Text(name,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,
                color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium)
            if(selected)Icon(LedgerIcons.Check,null,Modifier.size(12.dp),tint=MaterialTheme.colorScheme.primary)
        }
    } else Column(modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        CategoryBadge(name,Modifier.size(32.dp),selected,icon)
        CategoryLabel(name,selected)
    }
}

@Composable
internal fun CategoryLabel(name: String,selected: Boolean=false,modifier: Modifier=Modifier) {
    ControlScale(1.25f) {
        Text(name,modifier.fillMaxWidth(),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelMedium,
            color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium)
    }
}
