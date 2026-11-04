package io.github.localledger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger

@RunWith(AndroidJUnit4::class)
class NativeBookMergeTest {
    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private lateinit var app: LedgerApplication
        private lateinit var database: String
        private lateinit var source: String
        private lateinit var target: String
        private lateinit var account: String
        @JvmStatic @BeforeClass fun seed() {
            val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
            }
            check(avd == "LocalLedger_API35")
            app = instrumentation.targetContext.applicationContext as LedgerApplication
            AppStrings.initialize(chineseCopyContext(app))
            File(app.filesDir, "book-merge-review").listFiles()?.filter { it.isFile && it.extension == "png" }?.forEach { check(it.delete()) }
            database = "book-merge-${System.nanoTime()}.db"
            runBlocking(Dispatchers.IO) { LedgerStore(app, database).use { store ->
                source = store.saveBook("待整合"); target = store.saveBook("日常主账")
                account = store.saveAccount("共用钱包", BigInteger("10000"))
                val now = System.currentTimeMillis()
                store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1234), "合成支出", now, bookId = source, accountId = account), "source-uid")
                store.createEntry(EntryDraft(TransactionType.INCOME, Money(2000), "合成收入", now, bookId = target, accountId = account), "target-uid")
                val deleted = store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(100), "已删除合成账", now, bookId = source, accountId = account), "deleted-uid")
                store.deleteEntry(deleted, 0); store.setCurrentBook(source)
            } }
            app.javaClass.getDeclaredField("graph\$delegate").apply { isAccessible = true }.set(app, lazy { LedgerGraph(app, LedgerStore(app, database)) })
        }
        @JvmStatic @AfterClass fun close() {
            if (::app.isInitialized) instrumentation.runOnMainSync { app.graph.close() }
            if (::database.isInitialized) app.deleteDatabase(database)
        }
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = app.graph
    private fun ready() = compose.waitUntil(15_000) { !graph.loading.value && !graph.busy.value }
    private fun click(text: String) { val node = compose.onNodeWithText(text); if (!node.isDisplayed()) node.performScrollTo(); node.performClick() }
    private fun capture(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(app.filesDir, "book-merge-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun sharedAccountsAndReviewedMergeSurviveRecreationWithoutLosingMoney() {
        ready()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK) }
        val before = graph.snapshot.value.catalog.accounts
        click("设置"); compose.onAllNodesWithText("选择记忆").assertCountEquals(0)
        click("账户"); click("共用钱包")
        compose.onAllNodesWithText("账本").assertCountEquals(0)
        capture("global-account-dark")
        compose.onNodeWithContentDescription("返回").performClick(); ready()
        compose.onNodeWithContentDescription("返回").performClick(); ready()
        click("账本"); click("待整合"); click("合并到其他账本")
        compose.onNode(hasText("合并到") and hasClickAction()).performClick()
        compose.onNode(hasText("日常主账") and isSelectable()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("移入 1 条账单，合并后共 2 条。").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("移入 1 条账单，合并后共 2 条。").assertIsDisplayed()
        compose.activityRule.scenario.recreate(); ready()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("移入 1 条账单，合并后共 2 条。").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("移入 1 条账单，合并后共 2 条。").assertIsDisplayed()
        assertEquals(before, graph.snapshot.value.catalog.accounts)
        capture("book-merge-dark")
        compose.runOnIdle { graph.setAppearance(Appearance.LIGHT) }; capture("book-merge-light")
        click("合并账本"); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.books.none { it.id == source } }
        assertEquals(before, graph.snapshot.value.catalog.accounts)
        runBlocking(Dispatchers.IO) { LedgerStore(app, database).use { store ->
            val data = LedgerExchange.decode(store.exportExchange())
            assertEquals(setOf("source-uid", "target-uid", "deleted-uid"), data.entries.map { it.uid }.toSet())
            assertTrue(data.entries.all { it.bookId == target }); assertEquals(2, data.entries.count { !it.deleted })
            store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        } }
        click("默认账本"); click("删除")
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick(); ready()
        compose.waitUntil(10_000) { graph.snapshot.value.catalog.books.size == 1 }
        assertEquals(target, graph.snapshot.value.catalog.books.single().id)
        assertEquals(account, graph.snapshot.value.catalog.accounts.single().id)
        capture("book-merged-light")
    }
}
