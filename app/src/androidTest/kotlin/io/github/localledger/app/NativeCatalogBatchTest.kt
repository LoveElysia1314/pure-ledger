package io.github.localledger.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger

/** Real pointer/IME and persistence checks for catalog changes, using disposable data. */
@RunWith(AndroidJUnit4::class)
class NativeCatalogBatchTest {
    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        private lateinit var app: LedgerApplication
        private lateinit var database: String
        private lateinit var travel: String
        private lateinit var account: String
        private lateinit var category: String
        private var prepared = false
        @JvmStatic @BeforeClass fun seed() {
            check(shell("getprop ro.boot.qemu.avd_name") == "LocalLedger_API35")
            app = instrumentation.targetContext.applicationContext as LedgerApplication
            AppStrings.initialize(chineseCopyContext(app))
            File(app.filesDir, "batch-review").listFiles()?.filter { it.isFile && it.extension == "png" }?.forEach { check(it.delete()) }
            database = "batch-${System.nanoTime()}.db"
            runBlocking(Dispatchers.IO) { LedgerStore(app, database).use { store ->
                travel = store.saveBook("分类管理")
                account = store.saveAccount("历史现金", BigInteger("100000"))
                category = store.snapshot().catalog.categories.single { it.name == "三餐" }.id
                store.saveCategory(TransactionType.EXPENSE, "自定义餐费", icon = "gift")
                store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1800), "合成餐馆", System.currentTimeMillis(),
                    bookId = travel, categoryId = category, accountId = account), "batch-native-entry")
            } }
            app.javaClass.getDeclaredField("graph\$delegate").apply { isAccessible = true }.set(app, lazy { LedgerGraph(app, LedgerStore(app, database)) })
            prepared = true
        }
        @JvmStatic @AfterClass fun close() {
            if (prepared) instrumentation.runOnMainSync { app.graph.close() }
            if (::app.isInitialized && ::database.isInitialized) app.deleteDatabase(database)
        }
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = app.graph
    private fun ready() = compose.waitUntil(15_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) { val node = compose.onNodeWithText(text); if (!node.isDisplayed()) node.performScrollTo(); node.performClick() }
    private fun capture(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(app.filesDir, "batch-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun hideKeyboard() {
        if (compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) {
            shell("input keyevent 4")
            compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
        }
    }
    private fun selectGroup(hidden: Boolean, count: Int) {
        val title = appString(if (hidden) R.string.ui_hidden_categories else R.string.ui_active_categories, count)
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasText(title))
        compose.onNode(hasText(appString(R.string.ui_select_all)) and hasAnyAncestor(hasAnyChild(hasText(title))))
            .performClick()
    }
    private fun categoryRow(name: String): SemanticsNodeInteraction {
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription(name))
        return compose.onNodeWithContentDescription(name)
    }
    private fun categoryAction(name: String, label: String) {
        val row = categoryRow(name).fetchSemanticsNode()
        compose.runOnIdle { assertTrue(row.config[SemanticsActions.CustomActions].single { it.label == label }.action()) }
        ready()
    }
    @Test fun batchMoveHideShowAndResetKeepCustomCategoryAndFinancialHistory() {
        ready()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK); graph.setQuery(LedgerQuery(bookId = travel).inMonth(java.time.YearMonth.now())) }
        ready()
        val before = graph.snapshot.value
        val custom = before.catalog.categories.single { it.name == "自定义餐费" }
        val picked = before.catalog.categories.filter { it.name in listOf("三餐", "交通") }.map { it.id }
        click("设置"); click("分类"); click(appString(R.string.ui_select))
        categoryRow("三餐").performClick()
        categoryRow("交通").performClick()
        click("移到最后"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.categories.filter { !it.hidden && it.type == TransactionType.EXPENSE }.takeLast(2).map { it.id } == picked }
        compose.onNodeWithText("搜索分类").performTextReplacement("自定义餐费"); hideKeyboard()
        selectGroup(false, 1)
        compose.onNodeWithText("内置").assertDoesNotExist()
        compose.onNodeWithText("自定义").assertDoesNotExist()
        compose.onNodeWithText("添加扩展分类 · 24 项").assertDoesNotExist()
        compose.activityRule.scenario.recreate(); ready()
        compose.onNodeWithText("已选 1 项").assertExists()
        capture("category-batch-dark")
        click("隐藏所选"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.categories.single { it.id == custom.id }.hidden }
        selectGroup(true, 1); click("显示所选"); ready()
        compose.waitUntil(10_000) { !graph.snapshot.value.catalog.categories.single { it.id == custom.id }.hidden }
        val customBeforeReset = graph.snapshot.value.catalog.categories.single { it.id == custom.id }
        click(appString(R.string.ui_done))
        compose.onNodeWithText("搜索分类").performTextReplacement(""); hideKeyboard()
        click("恢复默认分类")
        compose.onNode(hasText("恢复") and hasAnyAncestor(isDialog())).performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.categories.single { it.id == category }.position == 0L }
        capture("category-reset-dark")
        val after = graph.snapshot.value
        assertEquals(customBeforeReset, after.catalog.categories.single { it.id == custom.id })
        assertEquals(before.rows, after.rows); assertEquals(before.catalog.accounts, after.catalog.accounts)
    }

    @Test fun accessibleMovesCrossGroupsAndPersistWithoutChangingTheLedger() {
        ready()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK); graph.setQuery(LedgerQuery(bookId = travel).inMonth(java.time.YearMonth.now())) }
        ready()
        val before = graph.snapshot.value
        val custom = before.catalog.categories.single { it.name == "自定义餐费" }
        click("设置"); click("分类")
        compose.onNodeWithText("搜索分类").performTextReplacement(custom.name); hideKeyboard()
        if (custom.hidden) categoryAction(custom.name, appString(R.string.ui_show))
        categoryAction(custom.name, appString(R.string.ui_hide))
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.categories.single { it.id == custom.id }.hidden }
        capture("category-cross-group-dark")
        compose.activityRule.scenario.recreate(); ready()
        assertTrue(graph.snapshot.value.catalog.categories.single { it.id == custom.id }.hidden)
        categoryAction(custom.name, appString(R.string.ui_show))
        compose.waitUntil(10_000) { !graph.snapshot.value.catalog.categories.single { it.id == custom.id }.hidden }
        val original=graph.snapshot.value.catalog.categories.filter { it.type==TransactionType.EXPENSE }
        val peer=original.first { it.id!=custom.id }
        compose.runOnIdle { graph.arrangeCategories(TransactionType.EXPENSE,listOf(peer.id,custom.id),original.filter { it.id!=peer.id && it.id!=custom.id }.map { it.id }){} };ready()
        try {
            compose.onNodeWithText("搜索分类").performTextReplacement("");hideKeyboard()
            val from=categoryRow(custom.name).fetchSemanticsNode().boundsInRoot.center
            val to=compose.onNodeWithText(appString(R.string.ui_hidden_categories,original.size-2)).fetchSemanticsNode().boundsInRoot.center
            categoryRow(custom.name).performTouchInput {
                down(center);advanceEventTime(700);moveBy(to-from+androidx.compose.ui.geometry.Offset(0f,4f));up()
            }
            ready();compose.waitUntil(10_000){graph.snapshot.value.catalog.categories.single { it.id==custom.id }.hidden}
            capture("category-drag-cross-group-dark")
        } finally {
            ready();compose.runOnIdle { graph.arrangeCategories(TransactionType.EXPENSE,original.filterNot { it.hidden }.map { it.id },original.filter { it.hidden }.map { it.id }){} };ready()
        }
        assertEquals(before.rows, graph.snapshot.value.rows)
        assertEquals(before.catalog.accounts, graph.snapshot.value.catalog.accounts)
    }

    @Test fun usedAccountCanBeDeletedExcludedFromNewEntryAndRestored() {
        ready()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK); graph.setQuery(LedgerQuery(bookId = travel).inMonth(java.time.YearMonth.now())) }
        ready()
        val before = graph.snapshot.value
        click("设置"); click("账户"); click("历史现金"); click("删除")
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.accounts.single { it.id == account }.archived }
        compose.onNodeWithText("历史现金").assertDoesNotExist()
        capture("accounts-deleted-dark")
        compose.onNodeWithContentDescription("返回").performClick(); click("账单")
        compose.onNodeWithContentDescription("记一笔").performClick(); compose.showAmountKeypad(false)
        compose.onNodeWithContentDescription("账户").performClick()
        compose.onNode(hasText("历史现金") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasText("未分配") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        click("设置"); click("账户"); click("已删除 1"); click("历史现金")
        capture("account-restore-dark")
        click("恢复账户"); ready()
        compose.waitUntil(10_000) { !graph.snapshot.value.catalog.accounts.single { it.id == account }.archived }
        click("返回账户")
        compose.onNodeWithText("历史现金").assertIsDisplayed()
        assertEquals(before.rows, graph.snapshot.value.rows)
        assertEquals(before.catalog.accounts, graph.snapshot.value.catalog.accounts)
    }
}
