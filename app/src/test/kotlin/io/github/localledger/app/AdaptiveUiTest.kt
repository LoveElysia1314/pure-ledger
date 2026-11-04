package io.github.localledger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.localledger.core.inDateRange
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import io.github.localledger.app.ui.LedgerBillPaneLayout
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveUiTest {
    @OptIn(ExperimentalMaterial3AdaptiveApi::class)
    @Test @Config(sdk=[34],qualifiers="w1000dp-h800dp-mdpi")
    fun separatingHingeStaysBetweenTheListAndDetailContent() {
        ready()
        compose.runOnIdle { compose.activity.setContent {
            LedgerBillPaneLayout(PaneScaffoldDirective(2,24.dp,1,0.dp,360.dp,listOf(Rect(480f,0f,520f,1000f))),
                {Text("List pane")},{Text("Detail pane")})
        } }
        val list=compose.onNodeWithTag("ledger-list-pane").fetchSemanticsNode().boundsInWindow
        val detail=compose.onNodeWithTag("ledger-detail-pane").fetchSemanticsNode().boundsInWindow
        assertTrue(list.right<=480f);assertTrue(detail.left>=520f)
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.close() } }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) { val node = compose.onNodeWithText(text); if (!node.isDisplayed()) node.performScrollTo(); node.performClick() }
    @Test @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-mdpi")
    fun wideSettingsUseTheRailAndColumnsAndHideNavigationForManagement() {
        ready(); click("设置")
        val navigation = compose.onNodeWithText("统计").fetchSemanticsNode().boundsInRoot
        assertTrue(navigation.right < 160f)
        val books = compose.onNodeWithText("账本").fetchSemanticsNode().boundsInRoot
        val appearance = compose.onNodeWithText("外观").fetchSemanticsNode().boundsInRoot
        assertTrue(appearance.left > books.right)
        click("账户"); compose.onAllNodesWithText("统计").assertCountEquals(0)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("自动记账").assertExists()
        click("关于"); compose.onNodeWithText("关闭").performClick()
    }
    @Test fun bundledLicensesCanBeReadAndBackReturnsThroughAbout() {
        ready(); click("设置"); click("关于"); click("开源许可")
        click("Lucide 图标")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("ISC License", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The MIT License", substring = true).assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("Lucide 图标").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("GPL-3.0-only", substring = true).assertIsDisplayed()
        click("关闭")
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h740dp-mdpi")
    fun narrowCustomDatesUseTheirOwnRowAndKeepBookAndActionsReachable() {
        ready()
        compose.runOnIdle { graph.setQuery(graph.query.value.inDateRange(LocalDate.of(2025,11,1), LocalDate.of(2026,10,31))) }; ready()
        val book = compose.onNodeWithContentDescription("选择账本").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val period = compose.onNodeWithContentDescription("选择时间范围").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(period.top >= book.bottom)
        compose.onNodeWithText("2025-11 – 2026-10").assertIsDisplayed()
        compose.onNodeWithContentDescription("记一笔").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选").assertIsDisplayed()
        val before = graph.query.value.fromMs!!
        compose.onNodeWithContentDescription("上一时段").performClick(); ready()
        assertTrue(graph.query.value.fromMs!! < before)
    }
}
