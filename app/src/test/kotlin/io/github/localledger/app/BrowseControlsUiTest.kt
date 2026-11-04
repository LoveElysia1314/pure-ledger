package io.github.localledger.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.ui.*
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigInteger
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseControlsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=compose.activity.ledgerGraph
    private fun ready()=compose.waitUntil(10_000){!graph.loading.value && !graph.busy.value}
    @After fun close(){compose.runOnUiThread{graph.close()}}
    @Test fun bookPeriodAndActionsStayInTheSamePlaceAcrossTabsAndRangeTypes() {
        ready()
        fun controls()=listOf("选择账本","选择时间范围","筛选").map{compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot}
        val initial=controls()
        val billSummary=compose.onNodeWithTag("bills-summary").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("记一笔").assertIsDisplayed()
        compose.onNodeWithText("统计").performClick();assertEquals(initial,controls())
        compose.onNodeWithContentDescription("记一笔").assertDoesNotExist()
        val statisticsSummary=compose.onNodeWithTag("statistics-summary").fetchSemanticsNode().boundsInRoot
        assertEquals(billSummary.left,statisticsSummary.left,.1f);assertEquals(billSummary.width,statisticsSummary.width,.1f)
        compose.runOnIdle{graph.setQuery(graph.query.value.inYear(java.time.Year.now().value))};ready()
        assertEquals(initial,controls());compose.onNodeWithContentDescription("选择时间范围").assert(hasText("今年"))
        compose.onNodeWithContentDescription("选择时间范围").performClick()
        compose.onNode(hasText("全部时间") and hasAnyAncestor(isDialog())).performScrollTo().performClick();ready()
        assertEquals(initial,controls());assertNull(graph.query.value.fromMs);assertNull(graph.query.value.untilMs)
    }
    @Test fun pickerPresetsShareTheSameControlStyleAndOnlyExposeMonthsOrLarger() {
        ready();compose.onNodeWithContentDescription("选择时间范围").performClick()
        val month=compose.onNode(hasText("本月") and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot
        val all=compose.onNode(hasText("全部时间") and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot
        assertEquals(month.width,all.width,.1f);assertEquals(month.height,all.height,.1f)
        for(label in listOf("今天","本周","近七天"))compose.onNodeWithText(label).assertDoesNotExist()
    }
    @Test fun statisticsShareOneDisplayChoiceAndKeepTypeSwitchWithTheContent() {
        ready();compose.onNodeWithText("统计").performClick()
        val dimensions=compose.onNodeWithTag("statistics-dimensions").fetchSemanticsNode().boundsInRoot
        val display=compose.onNodeWithTag("statistics-display").fetchSemanticsNode().boundsInRoot
        assertTrue(display.left>dimensions.right);assertEquals(dimensions.center.y,display.center.y,1f)
        compose.onNodeWithTag("statistics-type-toggle").assert(hasText("支出",substring=true)).performClick()
        compose.onNodeWithTag("statistics-type-toggle").assert(hasText("收入",substring=true))
        compose.onNodeWithContentDescription("列表").performClick();compose.onNodeWithContentDescription("列表").assertIsSelected()
        compose.onNodeWithText("收支变化").performClick();compose.onNodeWithContentDescription("列表").assertIsSelected()
        compose.onNodeWithTag("statistics-type-toggle").assertDoesNotExist()
        compose.onNodeWithContentDescription("图表").performClick()
        compose.onNodeWithText("分类占比").performClick();compose.onNodeWithContentDescription("图表").assertIsSelected()
        compose.onNodeWithTag("statistics-type-toggle").assert(hasText("收入",substring=true))
    }
    @Test fun fiveDigitTotalsKeepTheirInlineRowsAndLongMoneyRemainsComplete() {
        ready()
        val metrics=listOf(StatementMetric("收入",BigInteger("999999")),StatementMetric("支出",BigInteger("500000")),StatementMetric("结余",BigInteger("499999")))
        fun show(values:List<StatementMetric>){compose.runOnIdle{compose.activity.setContent{LedgerTheme{StatementMetrics(values)}}}}
        show(metrics)
        val before=compose.onNodeWithText("收入").fetchSemanticsNode().boundsInRoot
        val next=metrics.map{it.copy(amount=it.amount+BigInteger.ONE)}
        show(next)
        assertEquals(before,compose.onNodeWithText("收入").fetchSemanticsNode().boundsInRoot)
        val label=compose.onNodeWithText("收入").fetchSemanticsNode().boundsInRoot
        val amount=compose.onNodeWithText("¥ 10000.00").fetchSemanticsNode().boundsInRoot
        assertTrue(amount.left>label.right);assertTrue(kotlin.math.abs(amount.top-label.top)<16f)
        val huge=BigInteger.valueOf(Long.MAX_VALUE)*BigInteger.TEN
        show(listOf(StatementMetric("收入",huge)))
        compose.onNodeWithText(statementAmount(huge)).assertIsDisplayed()
    }
}
