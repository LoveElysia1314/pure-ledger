package io.github.localledger.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.github.localledger.app.ui.*
import io.github.localledger.core.CategoryPresets
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContrastTest {
    private fun ratio(fg: Color, bg: Color) = ColorUtils.calculateContrast(fg.toArgb(), bg.toArgb())
    @Test fun platformWindowsAndPickersUseTheComposePalette() {
        val resources=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().resources
        listOf(false,true).forEach { dark ->
            val colors=ledgerColors(dark)
            assertEquals(colors.primary.toArgb(),resources.getColor(if(dark)R.color.ledger_primary_dark else R.color.ledger_primary_light,null))
            assertEquals(colors.background.toArgb(),resources.getColor(if(dark)R.color.ledger_background_dark else R.color.ledger_background_light,null))
        }
    }
    @Test fun enabledTextControlsAndFinancialValuesHaveReadableContrastInBothThemes() {
        val report = JSONObject()
        listOf(false, true).forEach { dark ->
            val c = ledgerColors(dark)
            val values = JSONObject()
            fun check(name: String, fg: Color, bg: Color, minimum: Double = 4.5) {
                val contrast = ratio(fg, bg); values.put(name, contrast)
                assertTrue("$dark $name contrast $contrast < $minimum", contrast >= minimum)
            }
            listOf(c.background, c.surface, c.surfaceContainerLow, c.surfaceContainer, c.surfaceContainerHighest).forEachIndexed { i, bg ->
                check("body-$i", c.onSurface, bg); check("secondary-$i", c.onSurfaceVariant, bg)
                check("outline-$i", c.outline, bg, 3.0)
                // outlineVariant separates surfaces; actionable glyphs and focus use the stronger roles.
                financialColors(dark).let { f ->
                    check("expense-$i",f.expense,bg);check("income-$i",f.income,bg);check("transfer-$i",f.transfer,bg)
                }
            }
            check("button", c.onPrimary, c.primary)
            check("selected", c.onPrimaryContainer, c.primaryContainer)
            check("selected-secondary", c.onSecondaryContainer, c.secondaryContainer)
            check("action", c.primary, c.surfaceContainerLow)
            check("repeat", c.tertiary, c.surfaceContainerLow)
            check("error", c.error, c.surfaceContainerLow)
            check("summary-label", c.onSurfaceVariant, c.primaryContainer)
            NoticeKind.entries.forEach { kind -> feedbackColors(kind,dark).let { check("feedback-$kind",it.content,it.container) } }
            report.put(if (dark) "dark" else "light", values)
        }
        File("build/ui-previews/contrast-ratios.json").apply { parentFile!!.mkdirs(); writeText(report.toString(2)) }
    }
    @Test fun categorySymbolsRemainDistinctWhenSelectedAndUnselected() {
        val symbols = io.github.localledger.core.CategoryIcons.keys
        listOf(false, true).forEach { dark ->
            val c = ledgerColors(dark)
            symbols.forEach { symbol ->
                val fg = categoryTint(symbol, dark)
                listOf(c.surface, c.surfaceContainerLow, c.surfaceContainer).forEach { surface ->
                    val bg = fg.copy(alpha = .105f).compositeOver(surface)
                    assertTrue("$dark $symbol unselected: ${ratio(fg, bg)}", ratio(fg, bg) >= 3.0)
                }
                assertTrue("$dark $symbol selected", ratio(c.onPrimaryContainer, c.primaryContainer) >= 3.0)
            }
        }
    }
}
