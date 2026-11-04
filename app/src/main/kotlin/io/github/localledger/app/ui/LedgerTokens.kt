package io.github.localledger.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.localledger.app.NoticeKind
import io.github.localledger.app.UiNotice

internal data class FeedbackColors(val container: Color, val content: Color)
internal data class FinancialColors(val expense: Color, val income: Color, val transfer: Color)
internal fun financialColors(dark: Boolean) = if (dark)
    FinancialColors(Color(0xFFEFA596),Color(0xFF83D6B8),Color(0xFFA6CADF)) else
    FinancialColors(Color(0xFFA34537),Color(0xFF1D6754),Color(0xFF376A86))
internal object LedgerShapes {
    val key=RoundedCornerShape(8.dp)
    val action=RoundedCornerShape(12.dp)
    val field=RoundedCornerShape(12.dp)
    val tag=RoundedCornerShape(10.dp)
    val selection=RoundedCornerShape(10.dp)
    val icon=RoundedCornerShape(10.dp)
    val group=RoundedCornerShape(16.dp)
    val sheet=RoundedCornerShape(topStart=24.dp,topEnd=24.dp,bottomStart=16.dp,bottomEnd=16.dp)
}
internal fun statisticsColors(dark: Boolean): List<Color> = (if(dark)listOf(0xFFA1D3BD,0xFF9FB8F0,0xFFF4B58F,0xFFD3ADE8,0xFFEEA6C3,0xFF90CCD7,0xFFE5C477,0xFFBDCD8E)
    else listOf(0xFF256958,0xFF4566A8,0xFFB86034,0xFF84539C,0xFFA54669,0xFF397E8D,0xFF9C7722,0xFF687640)).map { Color(it) }
internal object LedgerSpacing {
    val tight = 4.dp
    val related = 8.dp
    val group = 12.dp
    val page = 16.dp
    val touch = 48.dp
    val transaction = 64.dp
}

internal fun feedbackColors(kind: NoticeKind, dark: Boolean): FeedbackColors = when (kind) {
    NoticeKind.SUCCESS -> if (dark) FeedbackColors(Color(0xFF193F2B),Color(0xFFBEF0CF)) else FeedbackColors(Color(0xFFE1F2E5),Color(0xFF16452C))
    NoticeKind.INFO -> if (dark) FeedbackColors(Color(0xFF293F49),Color(0xFFD2EAF5)) else FeedbackColors(Color(0xFFE0EDF2),Color(0xFF173D4C))
    NoticeKind.WARNING -> if (dark) FeedbackColors(Color(0xFF4A3C11),Color(0xFFFFDF8A)) else FeedbackColors(Color(0xFFFFF0C2),Color(0xFF5A4300))
    NoticeKind.ERROR -> ledgerColors(dark).let { FeedbackColors(it.errorContainer,it.onErrorContainer) }
}

internal fun categoryTint(symbol: String, dark: Boolean): Color {
    val color = when (symbol) {
        "meal", "cup", "fruit", "snack", "basket" -> Color(0xFF8E4C1F)
        "transport", "car", "travel", "phone", "device" -> Color(0xFF315D86)
        "home", "wallet", "chart", "bag" -> Color(0xFF17675D)
        "beauty", "gift", "heart", "baby" -> Color(0xFF913E56)
        else -> Color(0xFF5F4B8C)
    }
    return if (dark) lerp(color, Color.White, .42f) else color
}

internal class LedgerNoticeVisuals(val notice: UiNotice): androidx.compose.material3.SnackbarVisuals {
    override val message=notice.message
    override val actionLabel: String?=null
    override val withDismissAction=false
    override val duration=androidx.compose.material3.SnackbarDuration.Short
}

@Composable
internal fun LedgerSnackbar(data: androidx.compose.material3.SnackbarData) {
    val kind=(data.visuals as? LedgerNoticeVisuals)?.notice?.kind ?: NoticeKind.INFO
    val colors=feedbackColors(kind,LocalLedgerDark.current)
    if(kind==NoticeKind.SUCCESS) androidx.compose.material3.Snackbar(containerColor=colors.container,contentColor=colors.content) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Icon(LedgerIcons.Check,null,Modifier.size(20.dp))
            androidx.compose.material3.Text(data.visuals.message,Modifier.weight(1f))
        }
    } else androidx.compose.material3.Snackbar(data, containerColor=colors.container,contentColor=colors.content,
        actionColor=colors.content,dismissActionContentColor=colors.content)
}

/** Short controls have bounded typography; reading and editable content retain the OS scale. */
@Composable
internal fun ControlScale(maximum: Float=1f, content: @Composable () -> Unit) {
    val base=LocalDensity.current
    val scale=base.fontScale.coerceIn(1f,maximum)
    val density=remember(base.density,scale){Density(base.density,scale)}
    CompositionLocalProvider(LocalDensity provides density,content=content)
}
