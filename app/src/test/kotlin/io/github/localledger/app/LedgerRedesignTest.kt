package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedgerRedesignTest {
    @org.junit.Before fun useAllTimeForHistoricalFixtures() {
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
        compose.runOnIdle { graph.setQuery(io.github.localledger.core.LedgerQuery()) }
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.close() }; RuntimeEnvironment.setFontScale(1f) }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun seed() {
        ready(); val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use { store ->
            val account = store.saveAccount("微信", BigInteger.valueOf(200000))
            val data = listOf(Triple("三餐", "街角面馆", 2600L), Triple("交通", "地铁乘车", 400L),
                Triple("咖啡饮料", "咖啡小馆", 1800L), Triple("买菜", "社区生鲜", 3280L),
                Triple("学习", "城市书店", 4590L), Triple("日用品", "生活超市", 2190L),
                Triple("运动健身", "球馆", 6000L), Triple("水果", "水果店", 1680L))
            data.forEachIndexed { i, (name, merchant, amount) ->
                val category = store.snapshot().catalog.categories.single { it.name == name && it.type == TransactionType.EXPENSE }
                store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(amount), merchant, 1793419200000L - i * 3600000,
                    note = if (i == 0) "午餐" else "", categoryId = category.id, accountId = account))
            }
            store.createEntry(EntryDraft(TransactionType.INCOME, Money(850000), "十月工资", 1793332800000,
                categoryId = store.snapshot().catalog.categories.single { it.name == "工资" }.id, accountId = account))
        } }
        compose.runOnIdle { graph.refresh() }; ready()
    }
    @Test fun readingAndCategoryStatisticsRetainScopeAndRichLabelsInBothThemes() {
        seed(); capture("redesign-bills-light")
        compose.onNodeWithText("午餐").assertExists()
        click("统计"); capture("redesign-statistics-light")
        compose.onNodeWithText("运动健身").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("球馆").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("球馆").performClick(); capture("redesign-details-light")
        compose.onNodeWithText("− ¥ 60.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK) }; compose.waitForIdle()
        click("账单"); capture("redesign-bills-dark")
        click("统计"); capture("redesign-statistics-dark")
    }
    @Test fun categoryGridSearchPersistsSelectionAndManagerMovesCategoriesBetweenGroups() {
        ready(); compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("28.50")
        capture("redesign-entry-light")
        compose.onNodeWithContentDescription("搜索分类").performClick(); compose.onNodeWithText("搜索分类").assertIsDisplayed()
        capture("redesign-category-picker")
        compose.onNodeWithText("搜索分类").performTextInput("宠物")
        compose.onNode(hasText("宠物") and isSelectable()).performClick()
        compose.onNode(hasText("宠物") and isSelected()).assertExists()
        compose.onNodeWithText("保存").assertIsDisplayed().performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        assertEquals("宠物", graph.snapshot.value.catalog.categories.single { it.id == graph.snapshot.value.rows.single().categoryId }.name)
        compose.onNodeWithContentDescription("返回").performClick(); click("设置"); click("分类")
        val before = graph.snapshot.value
        val pet = before.catalog.categories.single { it.name == "宠物" }.id
        capture("redesign-category-manager")
        compose.onNode(hasText("账本") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("搜索分类").performTextReplacement("宠物")
        click("选择")
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("宠物"))
        compose.onNodeWithContentDescription("宠物").performClick()
        click("隐藏所选"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.categories.single { it.id == pet }.hidden }
        capture("redesign-category-hidden")
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("宠物"))
        compose.onNodeWithContentDescription("宠物").performClick()
        click("显示所选"); ready()
        compose.waitUntil(10_000) { !graph.snapshot.value.catalog.categories.single { it.id == pet }.hidden }
        click("完成")
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription("宠物"))
        compose.onNodeWithContentDescription("宠物").performClick()
        compose.onNodeWithText("名称").assertExists(); click("取消")
        val after = graph.snapshot.value
        assertEquals(before.catalog.categories.map { it.id }.toSet(), after.catalog.categories.map { it.id }.toSet())
        assertEquals(before.rows, after.rows); assertEquals(before.catalog.accounts, after.catalog.accounts)
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h740dp-night-mdpi")
    fun largeFontKeepsCategoryChoicesAndSaveUsable() {
        RuntimeEnvironment.setFontScale(2f); compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("1.00")
        compose.onNodeWithContentDescription("搜索分类").performClick(); compose.onNodeWithText("搜索分类").assertIsDisplayed().performTextInput("运动健身")
        compose.onNode(hasText("运动健身") and isSelectable()).performClick(); capture("redesign-entry-dark-font200")
        compose.onNodeWithText("保存").assertIsDisplayed().performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        assertEquals("运动健身", graph.snapshot.value.catalog.categories.single { it.id == graph.snapshot.value.rows.single().categoryId }.name)
    }
    private fun capture(name: String) {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val manager = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            val view = ReflectionHelpers.getField<List<android.view.View>>(manager, "mViews").last()
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        }
        File("build/ui-previews", "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
