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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InformationUiTest {
    @org.junit.Before fun useAllTimeForHistoricalFixtures() {
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
        compose.runOnIdle { graph.setQuery(io.github.localledger.core.LedgerQuery()) }
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.close() } }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun date(month: Int) = LocalDate.of(2026, month, 2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun seed(block: (LedgerStore) -> Unit) {
        ready(); val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use(block) }
        compose.runOnIdle { graph.refresh() }; ready()
    }
    @Test fun dailyTotalsUseAllRowsAndIncomeFilterPresentsIncome() {
        seed { store ->
            repeat(70) { store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(100), "合成消费", date(1) + it)) }
            store.createEntry(EntryDraft(TransactionType.INCOME, Money(3000), "合成收入", date(1)))
        }
        assertEquals(50, graph.snapshot.value.rows.size)
        compose.onNodeWithText("支出 ¥ 70.00").assertIsDisplayed()
        compose.onNodeWithText("收入 ¥ 30.00").assertIsDisplayed(); capture("information-day-totals")
        compose.runOnIdle { graph.setQuery(LedgerQuery(type = TransactionType.INCOME)) }; ready()
        compose.onNodeWithText("¥ 30.00").assertIsDisplayed()
        compose.onAllNodesWithText("支出").assertCountEquals(0)
        compose.onNodeWithText("收入 ¥ 30.00").assertIsDisplayed(); capture("information-income-scope")
    }
    @Test fun monthlyComparisonKeepsTransferFeesAndSelectedMonthHasOneSummary() {
        seed { store ->
            val from = store.saveAccount("账户甲", null); val to = store.saveAccount("账户乙", null)
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1200), "一月消费", date(1)))
            store.createEntry(EntryDraft(TransactionType.INCOME, Money(5000), "一月收入", date(1)))
            store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(1000), "", date(1), accountId = from, toAccountId = to, fee = Money(100)))
            store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(2400), "二月消费", date(2)))
            store.createEntry(EntryDraft(TransactionType.INCOME, Money(7000), "二月收入", date(2)))
            store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(3000), "", date(2), accountId = from, toAccountId = to, discount = Money(50)))
        }
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("收支变化").performClick();compose.onNodeWithContentDescription("列表").performScrollTo().performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("2026-02 · 3 笔"))
        compose.onNodeWithText("2026-02 · 3 笔").assertIsDisplayed()
        compose.onNodeWithText("¥ 46.50").assertIsDisplayed(); capture("information-month-comparison")
        compose.onNodeWithContentDescription("选择时间范围").performClick(); compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(2f)};compose.onNode(hasText("应用") and hasAnyAncestor(isDialog())).performClick(); ready()
        compose.onAllNodesWithText("共 3 笔").assertCountEquals(1)
        compose.onNodeWithContentDescription("选择时间范围").assert(hasText("2026-02"))
        compose.onAllNodesWithText("月份").assertCountEquals(0)
        compose.onNode(hasText("¥ 46.50") and hasAnyAncestor(hasTestTag("statistics-summary"))).assertIsDisplayed(); capture("information-month-scope")
    }
    @Test fun bookAndAccountRemainReachableAlongsideTheCategoryGrid() {
        seed { it.saveAccount("微信", null) }
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithContentDescription("账户").assertIsDisplayed().performClick()
        compose.onNode(hasText("微信") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("微信").assertIsDisplayed()
        compose.onNodeWithContentDescription("账本").assertIsDisplayed()
        compose.onNodeWithContentDescription("分类目录").assertIsDisplayed()
        capture("information-entry-context")
        compose.onNodeWithText("收入").performClick(); compose.onNodeWithText("微信").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick(); compose.onNodeWithText("放弃").performClick()
        assertEquals(0, graph.snapshot.value.count)
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
