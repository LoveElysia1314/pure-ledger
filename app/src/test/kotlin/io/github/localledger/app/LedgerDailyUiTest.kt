package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedgerDailyUiTest {
    @org.junit.Before fun useAllTimeForHistoricalFixtures() {
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
        compose.runOnIdle { graph.setQuery(io.github.localledger.core.LedgerQuery()) }
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.close() } }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun fill(label: String, text: String) {
        val node = compose.onNodeWithText(label)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performTextReplacement(text)
    }
    private fun back() = compose.onNodeWithContentDescription("返回").performClick()
    private fun seed(block: (LedgerStore) -> Unit) {
        ready()
        val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use(block) }
        compose.runOnIdle { graph.refresh() }; ready()
    }
    @Test fun accountCreationManualEditAndDeleteUpdateBalance() {
        ready(); click("设置"); click("账户"); click("新增账户")
        fill("名称", "钱包"); fill("当前余额（元）", "100.00"); click("保存"); ready()
        compose.onNodeWithText("¥ 100.00").assertExists()
        capture("account-management")
        back(); click("账单"); compose.onNodeWithContentDescription("记一笔").performClick()
        fill("金额（元）", "18.01"); compose.onNodeWithContentDescription("交易对方").performScrollTo().performClick(); fill("交易对方", "午饭"); click("确定")
        compose.onNodeWithContentDescription("账户").performClick();compose.onNode(hasText("钱包") and hasAnyAncestor(isDialog())).performClick(); capture("manual-entry")
        click("保存"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        assertEquals(BigInteger("8199"), graph.snapshot.value.catalog.accounts.single { it.name=="钱包" }.balanceMinor)
        click("修改"); fill("金额（元）", "19.01"); click("保存"); ready()
        assertEquals(BigInteger("8099"), graph.snapshot.value.catalog.accounts.single { it.name=="钱包" }.balanceMinor)
        click("删除账单"); compose.onNodeWithText("删除", useUnmergedTree = true).performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 0 }
        assertEquals(BigInteger("10000"), graph.snapshot.value.catalog.accounts.single { it.name=="钱包" }.balanceMinor)
    }
    @Test fun transferFormUsesSourceDebitAndNetReceived() {
        seed { it.saveAccount("现金", BigInteger("10000")); it.saveAccount("银行卡", BigInteger.ZERO) }
        compose.onNodeWithContentDescription("记一笔").performClick(); click("转账")
        fill("转出金额（元）", "20.00"); click("转出账户");compose.onNode(hasText("现金") and hasAnyAncestor(isDialog())).performClick()
        click("转入账户"); click("银行卡"); fill("手续费（元）", "1.00"); fill("优惠（元）", "0.20")
        compose.onNodeWithText("到账 ¥ 19.20").assertIsDisplayed(); capture("transfer-entry")
        click("保存"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        val accounts = graph.snapshot.value.catalog.accounts.associateBy { it.name }
        assertEquals(BigInteger("8000"), accounts.getValue("现金").balanceMinor)
        assertEquals(BigInteger("1920"), accounts.getValue("银行卡").balanceMinor)
        assertEquals(BigInteger.ZERO, graph.snapshot.value.expense)
    }
    @Test fun searchAndMonthFilterReachBeyondVisiblePage() {
        val zone = ZoneId.systemDefault()
        fun date(month: Int) = LocalDate.of(2026, month, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        seed { store ->
            repeat(55) { store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(100), "日常消费", date(10) + it)) }
            store.createEntry(EntryDraft(TransactionType.INCOME, Money(3210), "九月稿费", date(9), "特定搜索"))
        }
        compose.onNodeWithContentDescription("筛选").performClick()
        fill("搜索商户、备注、分类或账户", "特定搜索"); compose.onNodeWithContentDescription("选择时间范围").performScrollTo().performClick(); compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(9f)};compose.onNode(hasText("应用") and hasAnyAncestor(isDialog())).performClick(); click("应用"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
        compose.onNodeWithText("九月稿费", substring = true).assertIsDisplayed(); assertEquals(BigInteger("3210"), graph.snapshot.value.income)
        click("统计")
        compose.onNodeWithText("100.0% · 1 笔").assertIsDisplayed()
        compose.onAllNodesWithText("月份").assertCountEquals(0); capture("filtered-statistics")
    }
    @Test fun previewCancelCommitRepeatAndExportThroughGraph() {
        ready()
        val input = File(compose.activity.cacheDir, "synthetic-ledger.xlsx").apply { writeBytes(workbookFixture(listOf("8.01", "9.02"), true)) }
        fun preview() { compose.runOnIdle { graph.inspectFile(Uri.fromFile(input)) }; ready(); compose.waitUntil(10_000) { graph.preview.value != null } }
        preview(); compose.onNodeWithText("新增 2 条 · 重复 0 条 · 冲突 0 条").assertExists(); capture("import-preview")
        click("取消"); assertEquals(0, graph.snapshot.value.count)
        preview(); click("确认导入"); ready(); compose.waitUntil(10_000) { graph.snapshot.value.count == 2 }
        assertFalse(graph.snapshot.value.catalog.accounts.single().balanceKnown)
        preview(); compose.onNodeWithText("新增 0 条 · 重复 2 条 · 冲突 0 条").assertExists(); click("确认导入"); ready()
        val output = File(compose.activity.cacheDir, "synthetic-ledger.zip")
        compose.runOnIdle { graph.exportTo(Uri.fromFile(output)) }; ready()
        assertTrue(output.exists()); assertEquals(2, LedgerExchange.decode(output.readBytes()).entries.size)
        assertNull(graph.actionError.value)
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
