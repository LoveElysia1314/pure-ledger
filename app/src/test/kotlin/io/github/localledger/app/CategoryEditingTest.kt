package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoryEditingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }
    @After fun close() { compose.runOnIdle { graph.enable(false); graph.close() } }
    private fun manager() { ready(); compose.onNodeWithText("设置").performClick(); click("分类") }
    @Test fun iconRenameHideAndRestoreAreAvailableWithoutChangingExistingCategoryIdentity() {
        compose.runOnIdle { graph.setAppearance(Appearance.DARK) }
        manager(); val id = graph.snapshot.value.catalog.categories.single { it.name == "三餐" }.id
        compose.onNode(hasText("三餐") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("名称").performTextReplacement("午餐")
        click("选择")
        compose.onNodeWithContentDescription("分类图标目录").performScrollToNode(hasContentDescription("图标网络"))
        compose.onNodeWithContentDescription("图标网络").performClick()
        click("隐藏分类"); click("保存"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.categories.single { it.id == id }.hidden }
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("午餐"))
        compose.onNodeWithContentDescription("午餐").performClick()
        capture("completion-category-edit-dark")
        click("隐藏分类"); click("保存"); ready()
        compose.waitUntil(10_000) { !graph.snapshot.value.catalog.categories.single { it.id == id }.hidden }
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("午餐"))
        compose.onNodeWithContentDescription("午餐").assertIsDisplayed()
        val category = graph.snapshot.value.catalog.categories.single { it.id == id }
        assertEquals("wifi",category.icon); assertEquals("午餐",category.name); assertEquals(0,graph.snapshot.value.count)
    }
    @Test fun dragSavesTheOrderAndAccessibleMoveCanRestoreIt() {
        manager()
        fun order() = graph.snapshot.value.catalog.categories.filter { it.type == TransactionType.EXPENSE }.map { it.id }
        val original = order()
        val from=compose.onNodeWithContentDescription("三餐").fetchSemanticsNode().boundsInRoot.center
        val to=compose.onNodeWithContentDescription("交通").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithContentDescription("三餐").performTouchInput {
            down(center); advanceEventTime(700); moveBy(to-from+androidx.compose.ui.geometry.Offset(4f,0f)); up()
        }
        capture("completion-category-order")
        ready()
        compose.waitUntil(10_000) { order().first() != original.first() }
        assertEquals(original[1], order()[0]); assertEquals(original[0],order()[1])
        val node=compose.onNodeWithContentDescription("三餐").fetchSemanticsNode()
        compose.runOnIdle { node.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label=="上移" }.action() }
        ready();compose.waitUntil(10_000){order()==original}
    }
    @Test fun iconPageBackAndRecreationRetainTheFormWithoutSavingIt() {
        manager()
        val original = graph.snapshot.value.catalog.categories.single { it.name == "三餐" }
        click("三餐")
        compose.onNodeWithText("名称").performTextReplacement("早餐")
        click("选择")
        compose.onNodeWithText("早餐 · 图标").assertIsDisplayed()
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithText("早餐 · 图标").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("名称").assertTextContains("早餐")
        click("选择")
        compose.onNodeWithContentDescription("分类图标目录").performScrollToNode(hasContentDescription("图标网络"))
        compose.onNodeWithContentDescription("图标网络").performClick()
        click("取消")
        assertEquals(original, graph.snapshot.value.catalog.categories.single { it.id == original.id })
        assertEquals(0, graph.snapshot.value.count)
    }
    @Test fun newCategoryIsGlobalAndUsesTheManagersIncomeTab() {
        ready(); click("设置"); click("账本"); click("新增账本")
        compose.onNodeWithText("名称").performTextReplacement("旅行"); click("保存"); ready()
        val travel = graph.snapshot.value.catalog.books.single { it.name == "旅行" }.id
        compose.onNodeWithContentDescription("返回").performClick(); click("分类")
        click("收入"); compose.onNodeWithContentDescription("新增分类").performClick()
        compose.onNodeWithText("账本").assertDoesNotExist()
        compose.onNode(hasText("收入") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("名称").performTextReplacement("行程补贴"); click("保存"); ready()
        val created = graph.snapshot.value.catalog.categories.single { it.name == "行程补贴" }
        assertEquals(TransactionType.INCOME, created.type)
        assertEquals(DEFAULT_BOOK, graph.query.value.bookId)
        assertEquals(0, graph.snapshot.value.count)
    }
    private fun capture(name: String) {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        }
        File("build/ui-previews","$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
