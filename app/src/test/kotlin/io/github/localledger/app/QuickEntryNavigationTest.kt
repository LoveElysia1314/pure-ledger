package io.github.localledger.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.data.LedgerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickEntryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.close() } }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) { val node = compose.onNodeWithText(text); if (!node.isDisplayed()) node.performScrollTo(); node.performClick() }
    private fun back() = compose.onNodeWithContentDescription("返回").performClick()
    private fun request() { compose.runOnIdle { graph.requestQuickEntry() }; compose.waitUntil(10_000) { graph.quickEntry.value == 0L } }
    private fun seed(block: (LedgerStore) -> Unit) {
        ready()
        val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use(block) }
        compose.runOnIdle { graph.refresh() }; ready()
    }
    @Test fun quickEntryOpensFromReadOnlyPagesButKeepsCatalogEditsAndSelections() {
        var travel = ""
        seed { travel = it.saveBook("旅行") }
        compose.runOnIdle { graph.setQuery(graph.query.value.copy(bookId = travel)) }; ready()
        click("设置"); click("自动记账"); request()
        compose.onNodeWithContentDescription("金额键盘").assertIsDisplayed()
        compose.onNodeWithContentDescription("账本").assert(hasText("旅行")); back()
        click("账本"); request(); compose.onNodeWithContentDescription("金额键盘").assertIsDisplayed(); back()
        click("账本"); click("默认账本")
        compose.onNodeWithText("名称").performTextReplacement("未保存的账本名")
        request(); compose.onNodeWithText("名称").assert(hasText("未保存的账本名"))
        compose.onAllNodesWithContentDescription("金额键盘").assertCountEquals(0)
        compose.activityRule.scenario.recreate(); ready(); request()
        compose.onNodeWithText("名称").assert(hasText("未保存的账本名"))
        click("取消"); back(); click("分类")
        compose.onNode(hasText("账本") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("三餐"))
        compose.onNodeWithContentDescription("三餐").performClick()
        compose.onNodeWithText("名称").performTextReplacement("未保存的分类名")
        request(); compose.onNodeWithText("名称").assert(hasText("未保存的分类名"))
        compose.onAllNodesWithContentDescription("金额键盘").assertCountEquals(0)
        click("取消"); click("选择")
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("三餐"))
        compose.onNodeWithContentDescription("三餐").performClick()
        request(); compose.onNodeWithContentDescription("三餐").assertIsOn()
        compose.onAllNodesWithContentDescription("金额键盘").assertCountEquals(0)
        compose.activityRule.scenario.recreate(); ready(); request()
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("三餐"))
        compose.onNodeWithContentDescription("三餐").assertIsOn()
        click("完成"); request()
        compose.onNodeWithContentDescription("金额键盘").assertIsDisplayed()
        assertEquals(0, graph.snapshot.value.count)
        assertFalse(graph.snapshot.value.catalog.books.any { it.name == "未保存的账本名" })
        assertFalse(graph.snapshot.value.catalog.categories.any { it.name == "未保存的分类名" })
    }
    @Test fun managementReturnsToTheSameScrollPositionAndTheWholeAccountCardOpens() {
        seed { store -> repeat(14) { store.saveBook("账本%02d".format(it)) }; store.saveAccount("现金", BigInteger.ZERO) }
        click("设置"); click("账本")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("账本12"))
        click("账本12"); click("取消")
        compose.onNodeWithText("账本12").assertIsDisplayed()
        back(); click("账户")
        compose.onNodeWithText("现金").performTouchInput { click(Offset(width - 8f, height / 2f)) }
        compose.onNodeWithText("名称").assert(hasText("现金"))
    }
}
