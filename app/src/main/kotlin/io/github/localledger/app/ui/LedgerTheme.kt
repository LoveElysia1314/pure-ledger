package io.github.localledger.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.localledger.app.Appearance
import io.github.localledger.app.CategoryDisplay

private val Light = lightColorScheme(
    primary = Color(0xFF1D6754), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEFE5), onPrimaryContainer = Color(0xFF174736),
    secondary = Color(0xFF53685B), secondaryContainer = Color(0xFFDCEEE5), onSecondaryContainer = Color(0xFF173E33),
    onSecondary = Color.White, onBackground = Color(0xFF19231E), surfaceTint = Color(0xFF1D6754),
    surfaceContainerHigh = Color(0xFFE8EDE9), surfaceContainerHighest = Color(0xFFE0E7E2),
    surfaceContainerLowest = Color.White,
    surfaceDim = Color(0xFFD8E0DA), surfaceBright = Color.White,
    tertiary = Color(0xFF4B636A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE8EC), onTertiaryContainer = Color(0xFF213F48),
    background = Color(0xFFF5F7F5), surface = Color(0xFFF5F7F5),
    surfaceContainer = Color(0xFFEDF1EE), surfaceContainerLow = Color.White,
    onSurface = Color(0xFF19231E), onSurfaceVariant = Color(0xFF506057),
    outline = Color(0xFF718078), outlineVariant = Color(0xFFD5DED7),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF83D6B8), onPrimary = Color(0xFF0C3527),
    primaryContainer = Color(0xFF254C3C), onPrimaryContainer = Color(0xFFC5EEDB),
    secondary = Color(0xFFB5CBBB), secondaryContainer = Color(0xFF284F40), onSecondaryContainer = Color(0xFFD1EDDE),
    onSecondary = Color(0xFF20372A), onBackground = Color(0xFFE9F0EC), surfaceTint = Color(0xFF83D6B8),
    surfaceContainerHigh = Color(0xFF28332D), surfaceContainerHighest = Color(0xFF303D35),
    surfaceContainerLowest = Color(0xFF0B100D),
    surfaceDim = Color(0xFF101613), surfaceBright = Color(0xFF303D35),
    tertiary = Color(0xFFADCBD3), onTertiary = Color(0xFF15343C),
    tertiaryContainer = Color(0xFF2B454E), onTertiaryContainer = Color(0xFFD3E7ED),
    background = Color(0xFF101613), surface = Color(0xFF101613),
    surfaceContainer = Color(0xFF222D26), surfaceContainerLow = Color(0xFF1A231E),
    onSurface = Color(0xFFE9F0EC), onSurfaceVariant = Color(0xFFB8C8BE),
    outline = Color(0xFF879B8F), outlineVariant = Color(0xFF39493F),
)
@Composable
fun LedgerTheme(appearance: Appearance = Appearance.SYSTEM, categoryDisplay: CategoryDisplay=CategoryDisplay.AUTO, content: @Composable () -> Unit) {
    val dark = appearance.isDark(isSystemInDarkTheme())
    CompositionLocalProvider(LocalLedgerDark provides dark,LocalCategoryDisplay provides categoryDisplay) {
        MaterialTheme(colorScheme = ledgerColors(dark), typography = LedgerTypography,
            shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)), content = content)
    }
}
internal val LocalLedgerDark = staticCompositionLocalOf { false }
internal val LocalCategoryDisplay=staticCompositionLocalOf { CategoryDisplay.AUTO }
internal fun ledgerColors(dark: Boolean) = if (dark) Dark else Light
private val LedgerTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp, fontFeatureSettings = "tnum"),
    headlineSmall = TextStyle(fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp, fontFeatureSettings = "tnum"),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontFeatureSettings = "tnum"),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontFeatureSettings = "tnum"),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
)
