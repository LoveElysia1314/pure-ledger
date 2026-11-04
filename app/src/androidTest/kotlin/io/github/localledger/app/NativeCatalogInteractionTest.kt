package io.github.localledger.app

import androidx.compose.ui.geometry.Offset
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
class NativeCatalogInteractionTest {
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
            File(app.filesDir, "catalog-review").listFiles()?.filter { it.isFile && it.extension == "png" }?.forEach { check(it.delete()) }
            database = "catalog-${System.nanoTime()}.db"
            runBlocking(Dispatchers.IO) { LedgerStore(app, database).use { store ->
                travel = store.saveBook("旅行")
                account = store.saveAccount("旅途现金", BigInteger("100000"))
                category = store.snapshot().catalog.categories.single { it.name == "三餐" }.id
                store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1800), "合成餐馆", System.currentTimeMillis(),
                    bookId = travel, categoryId = category, accountId = account), "catalog-native-entry")
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
        val folder = File(app.filesDir, "catalog-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun categoryRow(name: String): SemanticsNodeInteraction {
        compose.onNodeWithContentDescription("分类排列").performScrollToNode(hasContentDescription(name))
        return compose.onNodeWithContentDescription(name)
    }
    @Test fun iconHideAndRealDragPreserveHistoricIdentityAmountsAndBalance() {
        shell("wm user-rotation lock 0")
        compose.waitUntil(10_000) { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        ready()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK); graph.setQuery(LedgerQuery(bookId = travel).inMonth(java.time.YearMonth.now())) }
        ready()
        val before = graph.snapshot.value
        fun order() = graph.snapshot.value.catalog.categories.filter { !it.hidden && it.type == TransactionType.EXPENSE }.map { it.id }
        val original = order()
        click("设置"); click("分类")
        compose.onNode(hasText("账本") and hasClickAction()).assertDoesNotExist()
        categoryRow("三餐").performClick()
        compose.onNodeWithText("名称").performTextReplacement("午餐")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
        shell("input keyevent 4")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
        click("选择")
        compose.onNodeWithContentDescription("分类图标目录").performScrollToNode(hasContentDescription("图标网络"))
        compose.onNodeWithContentDescription("图标网络").performClick()
        capture("category-edit-dark")
        click("隐藏分类"); click("保存"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.categories.single { it.id == category }.hidden }
        categoryRow("午餐").performClick()
        click("隐藏分类"); click("保存"); ready()
        compose.waitUntil(10_000) { !graph.snapshot.value.catalog.categories.single { it.id == category }.hidden }
        val from=categoryRow("午餐").fetchSemanticsNode().boundsInRoot.center
        val to=compose.onNodeWithContentDescription("交通").fetchSemanticsNode().boundsInRoot.center
        categoryRow("午餐").performTouchInput {
            down(center); advanceEventTime(700); moveBy(to-from+Offset(4f,0f)); up()
        }
        capture("category-drag-dark")
        ready()
        compose.waitUntil(10_000) { order().first() == original[1] }
        assertEquals(original[0], order()[1])
        val row = categoryRow("午餐").fetchSemanticsNode()
        compose.runOnIdle { assertTrue(row.config[SemanticsActions.CustomActions].single { it.label == appString(R.string.ui_move_up, "") }.action()) }
        ready(); compose.waitUntil(10_000) { order() == original }
        val saved = order()
        click("收入")
        listOf("AA", "稿费", "分红", "利息").forEach { label ->
            categoryRow(label).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("报销").assertDoesNotExist()
        compose.onNodeWithContentDescription("退款").assertDoesNotExist()
        capture("category-income-dark")
        compose.onNodeWithContentDescription("新增分类").performClick()
        compose.onNode(hasText("账本") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("收入") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("名称").performTextReplacement("旅行补贴")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
        // The platform IME animates independently from the Compose test clock.
        android.os.SystemClock.sleep(350)
        val saveCategory = compose.onNodeWithText("保存")
        saveCategory.performScrollTo().assertIsDisplayed()
        capture("category-create-ime-dark")
        saveCategory.performClick(); ready()
        val created = graph.snapshot.value.catalog.categories.single { it.name == "旅行补贴" }
        assertEquals(TransactionType.INCOME, created.type)
        compose.onNodeWithContentDescription("返回").performClick(); click("账单")
        capture("category-history-dark")
        val after = graph.snapshot.value
        assertEquals(before.count, after.count)
        assertEquals(before.expense, after.expense)
        assertEquals(before.rows.single().uid, after.rows.single().uid)
        assertEquals(before.rows.single().amount, after.rows.single().amount)
        assertEquals("午餐", after.catalog.categories.single { it.id == category }.name)
        assertEquals("wifi", after.catalog.categories.single { it.id == category }.icon)
        assertEquals(BigInteger("98200"), after.catalog.accounts.single { it.id == account }.balanceMinor)
        runBlocking(Dispatchers.IO) { LedgerStore(app, database).use { store ->
            val reopened = store.snapshot(query = LedgerQuery(bookId = travel))
            assertEquals(saved, reopened.catalog.categories.filter { !it.hidden && it.type == TransactionType.EXPENSE }.map { it.id })
            assertEquals("wifi", reopened.catalog.categories.single { it.id == category }.icon)
            assertEquals(1, reopened.count)
            assertEquals(BigInteger("98200"), reopened.catalog.accounts.single { it.id == account }.balanceMinor)
            assertEquals(reopened.catalog.categories, store.snapshot(query = LedgerQuery(bookId = DEFAULT_BOOK)).catalog.categories)
            assertTrue(reopened.catalog.categories.any { it.id == created.id })
        } }
    }
    @Test fun smallWindowAndDoubleFontKeepMoneyAndActionsReadable() {
        ready()
        val oldFont = shell("settings get system font_scale")
        val oldSize = Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1)
        try {
            shell("wm user-rotation lock 0")
            shell("wm size 960x2220")
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15_000) { app.resources.configuration.screenWidthDp == 320 && app.resources.configuration.fontScale == 2f }
            compose.runOnIdle { graph.setAppearance(Appearance.DARK); graph.setQuery(LedgerQuery(bookId = travel).inMonth(java.time.YearMonth.now())) }
            ready()
            val before = graph.snapshot.value.count
            val currentActivity = compose.activity
            compose.onNodeWithContentDescription("选择时间范围").assertIsDisplayed()
            capture("small-bills-dark-font200")
            compose.onNodeWithContentDescription("记一笔").performClick(); compose.showAmountKeypad(false)
            val amount = "92233720368547758.07"
            amount.forEach { char ->
                val key = compose.onNodeWithContentDescription("输入$char")
                if (!key.isDisplayed()) key.performScrollTo()
                key.performClick()
            }
            capture("small-entry-dark-font200")
            compose.onNodeWithText("金额（元）").assertTextContains(amount)
            compose.onNodeWithText(amount).assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            click("放弃")
            assertEquals(before, graph.snapshot.value.count)
            compose.onNodeWithContentDescription("筛选").performClick()
            compose.onNodeWithContentDescription("选择时间范围").performScrollTo().performClick()
            compose.onNode(hasText("自定义") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            compose.onNodeWithContentDescription("选择开始月份").assertIsDisplayed()
            compose.onNode(hasText("应用") and hasAnyAncestor(isDialog())).assertIsDisplayed();capture("small-month-range-font200")
            compose.onNodeWithText("取消").performClick()
        } finally {
            shell("wm size " + (oldSize ?: "reset"))
            shell("settings put system font_scale $oldFont")
        }
    }

}
