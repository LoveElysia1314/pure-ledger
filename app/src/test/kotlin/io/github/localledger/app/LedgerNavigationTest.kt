package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsControllerCompat
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.ui.withMonth
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedgerNavigationTest {
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
    private fun back() = compose.onNodeWithContentDescription("返回").performClick()
    private fun assertDark(dark: Boolean) {
        compose.runOnIdle {
            val window = compose.activity.window
            val bars = WindowInsetsControllerCompat(window, window.decorView)
            assertEquals(!dark, bars.isAppearanceLightStatusBars)
            assertEquals(!dark, bars.isAppearanceLightNavigationBars)
            assertEquals(compose.activity.resources.getColor(if(dark)R.color.ledger_background_dark else R.color.ledger_background_light,null), (window.decorView.background as ColorDrawable).color)
        }
    }
    @Test fun appearancePersistsAndNativePickerUsesTheSelectedTheme() {
        ready(); click("设置"); click("外观"); click("深色"); compose.waitForIdle()
        assertEquals(Appearance.DARK, graph.appearance.value); assertDark(true)
        capture("appearance-dark")
        compose.activityRule.scenario.recreate(); ready(); assertDark(true)
        click("账单"); compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithContentDescription("选择交易时间").performClick()
        compose.onNodeWithText("日期").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        back(); click("设置"); click("外观"); click("浅色"); compose.waitForIdle(); assertDark(false)
    }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h800dp-night-xxhdpi")
    fun followsSystemNightAndSupportsAnExplicitLightOverride() {
        ready(); assertDark(true)
        compose.runOnIdle { graph.setAppearance(Appearance.LIGHT) }; compose.waitForIdle(); assertDark(false)
        compose.runOnIdle { graph.setAppearance(Appearance.SYSTEM) }; compose.waitForIdle(); assertDark(true)
    }
    @Test fun monthAndBookSwitchesShareScopeAcrossBillsAndStatistics() {
        ready(); val context = compose.activity.applicationContext
        lateinit var travel: String
        fun date(y: Int, m: Int) = LocalDate.of(y, m, 2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking(Dispatchers.IO) { LedgerStore(context).use { store ->
            travel = store.saveBook("旅行")
            val category = store.saveCategory( TransactionType.EXPENSE, "出行")
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(101), "默认一月", date(2026, 1)))
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(222), "旅行十二月", date(2025, 12), bookId = travel, categoryId = category))
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(333), "旅行一月", date(2026, 1), bookId = travel, categoryId = category))
        } }
        compose.runOnIdle { graph.setQuery(LedgerQuery().withMonth(YearMonth.of(2026, 1))) }; ready()
        compose.onNodeWithContentDescription("选择账本").performClick(); click("旅行"); ready()
        compose.onNodeWithContentDescription("选择时间范围").performClick()
        compose.onNodeWithTag("month-year").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(2025f)};compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(12f)};click("应用"); ready()
        compose.onNodeWithText("旅行十二月").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一月").performClick(); ready()
        compose.onNodeWithText("旅行一月").assertIsDisplayed(); assertEquals(BigInteger("333"), graph.snapshot.value.expense)
        val scope = graph.query.value
        click("统计"); compose.onNodeWithText("100.0% · 1 笔").assertExists(); capture("navigation-statistics")
        assertEquals(scope, graph.query.value)
        click("账单"); compose.onNodeWithText("旅行一月").assertIsDisplayed(); capture("navigation-bills")
        compose.onNodeWithContentDescription("上一月").performClick(); ready()
        assertEquals(BigInteger("222"), graph.snapshot.value.expense)
    }
    @Test fun backKeepsUnsavedBillUntilDiscardedAndCatalogBackReturnsToItsList() {
        ready(); compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithContentDescription("交易对方").performScrollTo().performClick(); compose.onNodeWithText("交易对方").performTextInput("尚未保存"); click("确定")
        back(); compose.onNodeWithText("放弃本次修改？").assertIsDisplayed(); click("继续填写")
        compose.onNodeWithText("尚未保存").assertExists()
        back(); click("放弃"); assertEquals(0, graph.snapshot.value.count)
        click("设置"); click("账户"); click("新增账户"); back()
        compose.onNodeWithText("新增账户").assertIsDisplayed(); back()
        compose.onNodeWithText("自动记账").assertIsDisplayed()
    }
    @Test fun categorySearchFindsChoicesBeyondTheQuickGrid() {
        ready(); val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use { store ->
            repeat(12) { store.saveCategory( TransactionType.EXPENSE, "分类%02d".format(it)) }
        } }
        compose.runOnIdle { graph.refresh() }; ready()
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("1.00")
        compose.onNodeWithContentDescription("搜索分类").performClick(); compose.onNodeWithText("搜索分类").performTextInput("11")
        click("分类11"); click("保存"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        val row = graph.snapshot.value.rows.single()
        assertEquals("分类11", graph.snapshot.value.catalog.categories.single { it.id == row.categoryId }.name)
    }
    @Test fun statisticDrillDownIncludesOlderRowsAndReturnsToTheSameScope() {
        ready(); val context = compose.activity.applicationContext
        val jan = LocalDate.of(2026, 1, 2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking(Dispatchers.IO) { LedgerStore(context).use { store ->
            val category = store.saveCategory( TransactionType.EXPENSE, "小分类")
            repeat(70) { store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(100), "常见商户", jan + it + 1)) }
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(456), "较早的目标账单", jan, categoryId = category))
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(789), "上月目标账单", jan - 31L * 86400000, categoryId = category))
        } }
        compose.runOnIdle { graph.setQuery(LedgerQuery().withMonth(YearMonth.of(2026, 1))) }; ready()
        assertEquals(50, graph.snapshot.value.rows.size)
        click("统计"); val scope = graph.query.value
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("小分类")); compose.onNodeWithText("小分类").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("较早的目标账单").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 笔 · ¥ 4.56").assertExists()
        compose.onNodeWithText("2026-01-02 00:00", substring = true).assertExists(); capture("category-bills")
        click("较早的目标账单"); compose.onNodeWithText("账单详情").assertIsDisplayed()
        back(); compose.waitUntil(10_000) { compose.onAllNodesWithText("较早的目标账单").fetchSemanticsNodes().isNotEmpty() }
        back(); compose.onNodeWithText("统计").assertIsSelected(); assertEquals(scope, graph.query.value)
        assertEquals(71, graph.snapshot.value.count)
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h740dp-night-mdpi")
    fun largeTextKeepsMonthAndScopeControlsReachable() {
        RuntimeEnvironment.setFontScale(2f); compose.activityRule.scenario.recreate(); ready()
        compose.runOnIdle { graph.setQuery(LedgerQuery().withMonth(YearMonth.of(2026, 1))) }; ready()
        compose.onNodeWithContentDescription("选择时间范围").assertIsDisplayed().performClick()
        compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(12f)};click("应用"); ready()
        compose.onNodeWithContentDescription("上一月").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择账本").assertIsDisplayed()
        capture("navigation-dark-320-font200")
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
