package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.data.*
import io.github.localledger.app.ui.continuation
import io.github.localledger.app.ui.month
import io.github.localledger.app.ui.withMonth
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntryWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.close() }; RuntimeEnvironment.setFontScale(1f) }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) = compose.onNodeWithText(text).performClick()
    private fun key(key: String) = compose.onNodeWithContentDescription("输入$key").performClick()
    private fun seed(block: (LedgerStore) -> Unit) {
        ready(); val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use(block) }
        compose.runOnIdle { graph.refresh() }; ready()
    }
    @Test fun coldLaunchShowsThisMonthAndArrowsWorkInEmptyBooksAndAcrossYears() {
        ready(); assertEquals(YearMonth.now(), graph.query.value.month())
        assertEquals(DEFAULT_BOOK, graph.query.value.bookId)
        compose.onNodeWithContentDescription("上一月").assertIsDisplayed().performClick(); ready()
        assertEquals(YearMonth.now().minusMonths(1), graph.query.value.month())
        compose.onNodeWithContentDescription("下一月").performClick(); ready()
        assertEquals(YearMonth.now(), graph.query.value.month())
        compose.runOnIdle { graph.setQuery(graph.query.value.withMonth(YearMonth.of(2026, 1))) }; ready()
        compose.onNodeWithContentDescription("上一月").performClick(); ready()
        assertEquals(YearMonth.of(2025, 12), graph.query.value.month())
        click("统计"); compose.onNodeWithContentDescription("下一月").assertIsDisplayed().performClick(); ready()
        assertEquals(YearMonth.of(2026, 1), graph.query.value.month())
        capture("workspace-month-navigation")
    }
    @Test fun bookCardsCanChooseADefaultAndDeleteTheInitialAndLastBooks() {
        ready(); click("设置"); click("账本"); capture("workspace-books-light")
        click("默认账本"); click("设为默认账本"); click("保存"); ready()
        assertEquals(DEFAULT_BOOK, graph.snapshot.value.selection.defaultBookId)
        click("新增账本"); compose.onNodeWithText("名称").performTextReplacement("旅行"); click("保存"); ready()
        val travel = graph.snapshot.value.catalog.books.single { it.name == "旅行" }.id
        click("使用"); ready(); assertEquals(travel, graph.query.value.bookId)
        click("默认账本"); click("删除"); compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick(); ready()
        assertEquals(listOf(travel), graph.snapshot.value.catalog.books.map { it.id })
        assertNull(graph.snapshot.value.selection.defaultBookId)
        click("旅行"); click("删除"); compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick(); ready()
        val replacement = graph.snapshot.value.catalog.books.single()
        assertEquals("默认账本", replacement.name); assertNotEquals(DEFAULT_BOOK, replacement.id)
        assertEquals(replacement.id, graph.query.value.bookId)
        compose.runOnIdle { graph.setAppearance(Appearance.DARK) }; capture("workspace-books-dark")
    }
    @Test fun currentBookWinsOverFallbackAndOldMerchantMemoryAfterRestart() {
        var travel = ""
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(10), "合成商户", "workspace-book", null, "v1", 1)
        seed { store ->
            travel = store.saveBook("旅行", defaultBook = true)
            store.saveCandidate(ConfirmedEntry(source, bookId = travel), "workspace-book-memory")
            store.setCurrentBook(DEFAULT_BOOK)
        }
        compose.runOnIdle { graph.setQuery(graph.query.value.copy(bookId = DEFAULT_BOOK)) }; ready()
        runBlocking { assertEquals(DEFAULT_BOOK, graph.prepare(source).draft.bookId) }
        compose.onNodeWithContentDescription("选择账本").performClick(); click("旅行"); ready()
        runBlocking { assertEquals(travel, graph.prepare(source).draft.bookId) }
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithContentDescription("账本").assert(hasText("旅行"))
    }
    @Test fun normalEntryShowsTheCompleteStockCatalogAndKeypadWithoutPageScrolling() {
        ready(); compose.runOnIdle { graph.setAppearance(Appearance.LIGHT) }
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithContentDescription("备注").assertIsFocused()
        compose.onNodeWithText("三餐").assertIsDisplayed(); compose.onNodeWithText("其他支出").assertIsDisplayed()
        compose.onNodeWithText("未分类").assertDoesNotExist(); compose.onNodeWithText("保存").assertIsDisplayed()
        capture("workspace-entry-light")
        compose.runOnIdle { graph.setAppearance(Appearance.DARK) }; capture("workspace-entry-dark")
        click("三餐")
        listOf("0", ".", "1", "+", "0", ".", "2").forEach(::key)
        compose.onNodeWithContentDescription("交易对方").performScrollTo().performClick()
        compose.onNodeWithText("备注").performTextReplacement("午餐备注")
        compose.onNodeWithText("交易对方").performTextReplacement("合成商户")
        click("确定")
        compose.onNodeWithText("午餐备注").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择交易时间").performClick()
        compose.onNodeWithText("时间").performClick()
        compose.onNodeWithContentDescription("切换时间输入").performClick()
        compose.onNodeWithText("小时").performTextReplacement("8")
        compose.onNodeWithText("分钟").performTextReplacement("9")
        click("完成")
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithText("0.1+0.2").assertExists(); compose.onNodeWithText("午餐备注").assertIsDisplayed()
        click("保存"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        val row = graph.snapshot.value.rows.single()
        assertEquals(30L, row.amount.minor); assertEquals("合成商户", row.merchant); assertEquals("午餐备注", row.note)
        val occurred = java.time.Instant.ofEpochMilli(row.occurredAtMs).atZone(java.time.ZoneId.systemDefault())
        assertEquals(8, occurred.hour); assertEquals(9, occurred.minute)
        assertEquals("三餐", graph.snapshot.value.catalog.categories.single { it.id == row.categoryId }.name)
    }
    @Test fun noteIsEditedInlineAndNewUnassignedEntriesUseOtherExpenseOrIncome() {
        ready()
        for (type in listOf(TransactionType.EXPENSE,TransactionType.INCOME)) {
            compose.onNodeWithContentDescription("记一笔").performClick()
            compose.onNodeWithContentDescription("备注").assertIsFocused().performTextReplacement("直接输入备注 ${type.name}")
            if (type==TransactionType.INCOME) click("收入")
            compose.onNodeWithText("未分类").assertDoesNotExist()
            compose.onNodeWithText("金额（元）").performTextReplacement("12.34")
            click("保存");ready()
            compose.waitUntil(10_000){graph.snapshot.value.count==if(type==TransactionType.EXPENSE)1 else 2}
            val row=graph.snapshot.value.rows.single { it.type==type }
            assertNotNull(row.accountId)
            assertEquals("直接输入备注 ${type.name}",row.note)
            val category=graph.snapshot.value.catalog.categories.single { it.id==row.categoryId }
            assertEquals(if(type==TransactionType.EXPENSE)"EXPENSE:其他支出" else "INCOME:其他收入",category.presetKey)
            compose.onNodeWithContentDescription("返回").performClick()
        }
    }
    @Test fun anEmptyActiveAccountDirectoryCannotSaveAnUnassignedEntry() {
        seed { store -> store.snapshot().catalog.activeAccounts.forEach { store.deleteCatalog("accounts",it.id) } }
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("12.34")
        click("保存")
        compose.onNodeWithText("请选择账户").assertIsDisplayed()
        assertEquals(0,graph.snapshot.value.count)
        compose.onNodeWithContentDescription("账户").performClick()
        compose.onNodeWithText("未分配").assertDoesNotExist()
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h740dp-night-mdpi")
    fun largeFontKeepsCategorySelectionAmountAndSaveReachable() {
        RuntimeEnvironment.setFontScale(2f); compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithContentDescription("搜索分类").performClick()
        compose.onNodeWithText("搜索分类").performTextReplacement("运动健身")
        compose.onNode(hasText("运动健身") and isSelectable()).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("搜索分类").performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("92233720368547758.07")
        compose.onNodeWithText("保存").assertIsDisplayed()
        capture("workspace-entry-dark-font200")
        click("保存"); ready(); compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        assertEquals(Long.MAX_VALUE, graph.snapshot.value.rows.single().amount.minor)
    }
    @Test fun keypadGlyphsAndTouchTargetsStayStableWhileReadingFieldsFollowSystemTextSize() {
        ready();compose.onNodeWithContentDescription("记一笔").performClick()
        fun textHeight(text: String): Int {
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            return layouts.single().size.height
        }
        val glyph=textHeight("8")
        val note=compose.onNodeWithContentDescription("备注").fetchSemanticsNode().boundsInRoot.height
        val amount=textHeight("0.00")
        RuntimeEnvironment.setFontScale(2f);compose.activityRule.scenario.recreate();ready()
        assertEquals(glyph,textHeight("8"))
        assertTrue(compose.onNodeWithContentDescription("备注").fetchSemanticsNode().boundsInRoot.height>note)
        assertTrue(textHeight("0.00")>amount)
        val key=compose.onNodeWithContentDescription("输入8").fetchSemanticsNode().boundsInRoot
        val density=compose.activity.resources.displayMetrics.density
        assertTrue(key.width/density>=48f && key.height/density>=48f)
    }
    @Test fun normalAmountLabelAndPriceShareTheSameBaseline() {
        ready();compose.onNodeWithContentDescription("记一笔").performClick()
        fun baseline(text: String): Float {
            val node=compose.onNodeWithText(text,useUnmergedTree=true)
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            return node.fetchSemanticsNode().boundsInRoot.top+layouts.single().firstBaseline
        }
        val difference=kotlin.math.abs(baseline("金额（元）")-baseline("0.00"))
        assertTrue("Amount label and value baseline differ by $difference px",difference<=compose.activity.resources.displayMetrics.density)
    }
    @Test fun repeatEntryRetainsChoicesAndTimeButUsesANewReceiptAndEmptyAmountAfterSuccess() {
        var account = ""
        seed { account = it.saveAccount("现金", java.math.BigInteger("10000")) }
        compose.onNodeWithContentDescription("记一笔").performClick()
        click("交通")
        compose.onNodeWithContentDescription("账户").performClick();compose.onNode(hasText("现金") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("选择交易时间").performClick()
        compose.onNodeWithText("时间").performClick()
        compose.onNodeWithContentDescription("切换时间输入").performClick()
        compose.onNodeWithText("小时").performTextReplacement("8"); click("完成")
        compose.onNodeWithText("金额（元）").performTextReplacement("1.234")
        compose.onNodeWithContentDescription("保存并再记").performClick()
        ready(); assertEquals(0, graph.snapshot.value.count)
        compose.onNodeWithText("1.234").assertExists()
        compose.onNodeWithText("金额（元）").performTextReplacement("1.23")
        compose.onNodeWithContentDescription("保存并再记").performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        val first = graph.snapshot.value.rows.single()
        compose.onNodeWithText("0.00").assertExists(); compose.onNodeWithContentDescription("账户").assert(hasText("现金"))
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithText("0.00").assertExists()
        compose.onNodeWithText("金额（元）").performTextReplacement("1.23")
        click("保存"); ready(); compose.waitUntil(10_000) { graph.snapshot.value.count == 2 }
        val rows = graph.snapshot.value.rows
        assertEquals(2, rows.map { it.id }.distinct().size)
        assertTrue(rows.all { it.amount.minor == 123L && it.accountId == account && it.categoryId == first.categoryId && it.occurredAtMs == first.occurredAtMs })
        assertEquals(java.math.BigInteger("9754"), graph.snapshot.value.catalog.accounts.single().balanceMinor)
    }
    @Test fun repeatEntryClearsSearchOnlyAfterASuccessfulSave() {
        ready(); compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("1.234")
        compose.onNodeWithContentDescription("搜索分类").performClick()
        compose.onNodeWithText("搜索分类").performTextInput("交通")
        val hide = compose.onNodeWithText("收起键盘")
        if (hide.isDisplayed()) hide.performClick()
        compose.onNodeWithContentDescription("保存并再记").performClick(); ready()
        assertEquals(0, graph.snapshot.value.count)
        compose.onNodeWithText("搜索分类").assertTextContains("交通")
        compose.onNodeWithText("金额（元）").performTextReplacement("1.23")
        compose.onNodeWithContentDescription("保存并再记").performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        compose.onAllNodesWithText("搜索分类").assertCountEquals(0)
        compose.onNodeWithText("三餐").assertIsDisplayed()
        compose.onNodeWithText("0.00").assertExists()
    }
    @Test fun failedRepeatWriteKeepsTheDraftAndRetryCommitsOnce() {
        seed { it.writableDatabase.execSQL("CREATE TRIGGER reject_repeat BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") }
        compose.onNodeWithContentDescription("记一笔").performClick()
        click("交通"); key("4")
        compose.onNodeWithContentDescription("保存并再记").performClick()
        compose.waitUntil(10_000) { !graph.busy.value && graph.actionError.value != null }
        assertEquals(0, graph.snapshot.value.count)
        compose.onNodeWithText("金额（元）").assert(hasText("4"))
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithText("金额（元）").assert(hasText("4"))
        seed { it.writableDatabase.execSQL("DROP TRIGGER reject_repeat") }
        compose.onNodeWithContentDescription("保存并再记").performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        assertEquals(400L, graph.snapshot.value.rows.single().amount.minor)
        compose.onNodeWithText("0.00").assertExists()
    }
    @Test fun transferContinuationRestoresBothAccountsAndBalancePolicyWithoutFeeOrAmount() {
        val draft = EntryDraft(TransactionType.TRANSFER, Money(200), "对方", 123456L, "备注", "book", accountId = "a",
            toAccountId = "b", fee = Money(100), discount = Money(20), affectsBalance = false)
        val next = draft.continuation()
        assertEquals(1L, next.amount.minor); assertEquals(0L, next.fee.minor); assertEquals(0L, next.discount.minor)
        assertEquals("a", next.accountId); assertEquals("b", next.toAccountId); assertFalse(next.affectsBalance)
        assertEquals(123456L, next.occurredAtMs); assertEquals("", next.note); assertEquals("", next.merchant)
        with(io.github.localledger.app.ui.EntryContinuationSaver) {
            val saved = androidx.compose.runtime.saveable.SaverScope { true }.save(next)!!
            assertEquals(next, restore(saved))
        }
    }
    @Test @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeKeepsCatalogAndKeypadInSeparatePanes() {
        ready(); compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithText("三餐").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithContentDescription("输入8").assertIsDisplayed()
        val category = compose.onNodeWithText("三餐").fetchSemanticsNode().boundsInRoot
        val keypad = compose.onNodeWithContentDescription("金额键盘").fetchSemanticsNode().boundsInRoot
        assertTrue(category.right < keypad.left)
        capture("workspace-entry-landscape")
        click("三餐"); key("8"); click("保存"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        assertEquals(800L, graph.snapshot.value.rows.single().amount.minor)
    }
    @Test fun historicalDatesAndFarMonthsUseTheSameRangeAsTheLedger() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.of(1899,12,31)
        val occurred = date.atTime(10,20).atZone(zone).toInstant().toEpochMilli()
        seed { it.createEntry(EntryDraft(TransactionType.EXPENSE, Money(100), "早期记录", occurred), "early-date") }
        compose.runOnIdle { graph.setQuery(LedgerQuery().inMonth(YearMonth.of(1899,12))) }; ready()
        click("早期记录"); click("修改")
        compose.onNodeWithContentDescription("账户").performClick();compose.onNode(hasText("现金") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("选择交易时间").performClick()
        compose.onNodeWithText("时间").performClick()
        compose.onNodeWithContentDescription("切换时间输入").performClick()
        compose.onNodeWithText("小时").performTextReplacement("8")
        click("完成"); click("保存"); ready()
        val saved = java.time.Instant.ofEpochMilli(graph.snapshot.value.rows.single().occurredAtMs).atZone(zone)
        assertEquals(date, saved.toLocalDate()); assertEquals(8, saved.hour)
        assertEquals(100L, graph.snapshot.value.rows.single().amount.minor)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithContentDescription("选择时间范围").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { graph.setQuery(LedgerQuery().inMonth(YearMonth.of(9999,12))) }; ready()
        compose.onNodeWithContentDescription("筛选").performClick()
        compose.onNodeWithContentDescription("选择时间范围").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(YearMonth.of(9999,12), graph.query.value.month())
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
