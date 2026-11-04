package io.github.localledger.app

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.overlay.CandidateOverlay
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.ZoneId

/** Real compositor frames of synthetic data/source UI, using the unmodified production windows. */
@RunWith(AndroidJUnit4::class)
class NativeLayoutReviewTest {
    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        private lateinit var app: LedgerApplication
        private lateinit var database: String
        private var prepared = false
        @JvmStatic @BeforeClass fun seed() {
            check(shell("getprop ro.boot.qemu.avd_name") == "LocalLedger_API35")
            app = instrumentation.targetContext.applicationContext as LedgerApplication
            AppStrings.initialize(chineseCopyContext(app))
            File(app.filesDir, "layout-review").listFiles()?.filter { it.isFile && it.extension == "png" }?.forEach { check(it.delete()) }
            database = "layout-${System.nanoTime()}.db"
            runBlocking(Dispatchers.IO) { LedgerStore(app, database).use { store ->
                store.saveBook("日常", DEFAULT_BOOK); store.saveBook("旅行")
                val cash = store.saveAccount("现金", BigInteger("200000"))
                store.saveAccount("微信", BigInteger("150000"))
                val catalog = store.snapshot().catalog
                val expenses = catalog.categories.filter { it.type == TransactionType.EXPENSE }
                repeat(26) { index ->
                    val date = LocalDateTime.of(2026, 10, 31 - index / 6, 18 - index % 6, index % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val category = expenses[index % expenses.size]
                    store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1250L + index * 73), category.name + "商户", date,
                        note = if (index % 3 == 0) "日常记录" else "", categoryId = category.id, accountId = cash), "layout-$index")
                }
                store.createEntry(EntryDraft(TransactionType.INCOME, Money(1_000_000), "工资", LocalDateTime.of(2026,10,28,10,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    categoryId = catalog.categories.single { it.name == "工资" }.id, accountId = cash), "layout-income")
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
        compose.waitForIdle(); android.os.SystemClock.sleep(700)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("No compositor frame")
        val output = File(app.filesDir, "layout-review").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
    @Test fun rapidLedgerScrollingKeepsTheDayCaptionAtOnePosition() {
        ready();compose.runOnIdle{graph.setQuery(LedgerQuery(bookId=DEFAULT_BOOK).inMonth(java.time.YearMonth.of(2026,10)));graph.setAppearance(Appearance.LIGHT)};ready()
        val list=compose.onNode(hasScrollAction())
        var anchor:Float?=null
        repeat(4) { pass ->
            list.performTouchInput{if(pass<3)swipeUp(durationMillis=160) else swipeDown(durationMillis=160)}
            compose.waitForIdle()
            val bars=compose.onAllNodesWithTag("pinned-day").fetchSemanticsNodes()
            if(bars.isNotEmpty()) {
                val top=bars.single().boundsInRoot.top
                if(anchor==null)anchor=top else assertEquals(anchor!!,top,.5f)
            }
        }
        assertNotNull("Scrolled ledger exposes its fixed date layer",anchor)
        capture("fixed-day-after-fling")
    }
    @Test fun backgroundBatteryEntryOpensTheSystemPageAndReturns() {
        ready();click("设置");click("自动记账")
        compose.onNodeWithText("后台耗电").assertIsDisplayed().performClick()
        compose.waitUntil(10_000){shell("dumpsys activity activities").lineSequence().any{it.contains("topResumedActivity") && it.contains("com.android.settings")}}
        capture("background-battery-system")
        shell("input keyevent 4")
        compose.onNodeWithText("后台耗电").assertIsDisplayed()
        capture("automatic-battery-entry")
    }
    @Test fun tabletAndFoldSizedWindowsKeepSelectionAndUseListDetailPanes() {
        ready();compose.runOnIdle { graph.setQuery(LedgerQuery(bookId=DEFAULT_BOOK).inMonth(java.time.YearMonth.of(2026,10)));graph.setAppearance(Appearance.LIGHT) };ready()
        val before=graph.snapshot.value
        val oldSize=Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1)
        val oldDensity=Regex("Override density: ([0-9]+)").find(shell("wm density"))?.groupValues?.get(1)
        fun resize(size:String,density:Int,width:Int) {
            shell("wm density $density");shell("wm size $size")
            compose.waitUntil(15_000){app.resources.configuration.screenWidthDp==width};ready()
            compose.waitForIdle();android.os.SystemClock.sleep(600)
        }
        try {
            resize("1920x1200",240,1280)
            compose.onNodeWithTag("ledger-list-pane").assertIsDisplayed();compose.onNodeWithTag("ledger-detail-pane").assertIsDisplayed()
            capture("tablet-16-10-bills")
            val first=before.rows.first()
            compose.onAllNodesWithText(first.note.ifBlank{first.merchant}).onFirst().performClick()
            compose.onNodeWithTag("ledger-list-pane").assertIsDisplayed();compose.onNodeWithTag("ledger-detail-pane").assertIsDisplayed()
            capture("tablet-16-10-detail")
            resize("1768x2208",320,884)
            compose.onNodeWithTag("ledger-list-pane").assertIsDisplayed();capture("unfolded-detail")
            resize("816x2268",320,408)
            compose.onNodeWithTag("ledger-list-pane").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").assertIsDisplayed();compose.onNodeWithText(first.merchant).assertExists()
            capture("folded-detail")
            resize("1920x1200",240,1280)
            compose.onNodeWithTag("ledger-detail-pane").assertIsDisplayed();compose.onNodeWithText(first.merchant).assertExists()
            compose.onNodeWithContentDescription("关闭").performClick()
            click("统计");capture("tablet-16-10-category-share")
            click("收支变化");compose.onNodeWithContentDescription("列表").performClick();capture("tablet-16-10-trend-list")
            click("账单");compose.onNodeWithContentDescription("记一笔").performClick()
            compose.showAmountKeypad();capture("tablet-16-10-entry")
            assertEquals(before.rows,graph.snapshot.value.rows)
        } finally { shell("wm size "+(oldSize ?: "reset"));shell("wm density "+(oldDensity ?: "reset")) }
    }
    @Test fun compactPagesAndRealOverlayKeepContentAndActionsVisible() {
        ready(); compose.runOnIdle { graph.setQuery(LedgerQuery(bookId = DEFAULT_BOOK).inMonth(java.time.YearMonth.of(2026,10))); graph.setAppearance(Appearance.LIGHT) }; ready()
        capture("bills-light"); click("统计"); capture("statistics-light")
        compose.onNodeWithTag("statistics-type-toggle").performClick();capture("statistics-income")
        compose.onNodeWithTag("statistics-type-toggle").performClick()
        compose.onNodeWithContentDescription("列表").performClick();click("收支变化")
        compose.onNodeWithContentDescription("列表").assertIsSelected();capture("statistics-trend-list")
        compose.onNodeWithContentDescription("图表").performClick();click("分类占比")
        compose.onNodeWithContentDescription("图表").assertIsSelected()
        click("设置"); capture("settings-light")
        click("账本"); capture("books-light"); compose.onNodeWithContentDescription("返回").performClick()
        click("分类"); capture("categories-light"); compose.onNodeWithContentDescription("返回").performClick()
        click("账户"); capture("accounts-light"); compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK) }; capture("settings-dark")
        click("统计"); capture("statistics-dark"); click("账单"); capture("bills-dark")
        val oldRotation = shell("settings get system user_rotation"); val oldAuto = shell("settings get system accelerometer_rotation")
        try {
            for(rotation in listOf(1,3)) {
                shell("wm user-rotation lock $rotation");android.os.SystemClock.sleep(700)
                assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT,app.resources.configuration.orientation)
                assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,compose.activity.requestedOrientation)
                compose.onNodeWithContentDescription("筛选").performClick();compose.onNodeWithText("应用").assertIsDisplayed()
                compose.onNodeWithContentDescription("返回").performClick();capture("phone-rotation-$rotation")
            }
        } finally { shell("wm user-rotation lock $oldRotation"); if (oldAuto == "1") shell("wm user-rotation free") }
        compose.waitUntil(10_000) { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        val oldOp = Regex("SYSTEM_ALERT_WINDOW: (\\w+)").find(shell("appops get io.github.localledger.app SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        var overlay: CandidateOverlay? = null
        val oldEnabled = graph.enabled.value
        try {
            shell("appops set io.github.localledger.app SYSTEM_ALERT_WINDOW allow")
            compose.runOnIdle {
                compose.activity.setContent { Column(Modifier.fillMaxSize().background(Color(0xFF101010)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("交易详情", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("合成商户", color = Color.White); Text("−18.00", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    Text("交易成功", color = Color.White); Text("支付时间 2026-10-31 12:00:00", color = Color.White)
                } }
                graph.enable(true); graph.activityVisible = false
                graph.present(TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1800), "合成商户", "layout-source", "2026-10-31 12:00:00", "layout-v1", 1793419200000L))
            }
            compose.waitUntil(10_000) { graph.pending.value?.preparation != null }
            compose.runOnIdle { overlay = CandidateOverlay(app, graph, onFocusLost = { overlay?.close(); graph.discard() }); assertTrue(overlay!!.show()) }
            compose.onNodeWithText("纯净记账").assertIsDisplayed()
            compose.onNodeWithText("通讯").assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            capture("overlay-dark-expanded")
            compose.onNodeWithContentDescription("收起记账窗口").performClick()
            compose.waitUntil(10_000) { overlay?.expanded == false }; capture("overlay-dark-collapsed")
            compose.onNodeWithContentDescription("展开记账窗口").performClick()
            compose.waitUntil(10_000) { overlay?.expanded == true }
            compose.onNodeWithText("金额（元）").performTextReplacement("18.01")
            val view = CandidateOverlay::class.java.getDeclaredField("view").apply { isAccessible = true }.get(overlay) as android.view.View
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, (view.layoutParams as WindowManager.LayoutParams).type)
            compose.waitUntil(10_000) { view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
            compose.onNodeWithText("保存").assertIsDisplayed(); capture("overlay-dark-ime")
            compose.onNodeWithContentDescription("收起记账窗口").performClick()
            compose.waitUntil(10_000) { overlay?.expanded == false && view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            assertNotNull(graph.pending.value); capture("overlay-dark-edited-collapsed")
            compose.onNodeWithContentDescription("展开记账窗口").performClick()
            compose.waitUntil(10_000) { overlay?.expanded == true }
            compose.onNodeWithText("18.01").assertExists()
            val originalTime = compose.onNodeWithContentDescription("选择交易时间").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
            compose.onNodeWithContentDescription("选择交易时间").assertIsDisplayed().performClick()
            capture("overlay-date-wheel")
            compose.onNodeWithText("时间").performClick()
            capture("overlay-time-wheel")
            val wheel=compose.onNodeWithTag("time-hour")
            val beforeWheel=wheel.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
            wheel.performTouchInput{swipeUp(durationMillis=350)}
            compose.waitUntil(10_000){wheel.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]!=beforeWheel}
            android.os.SystemClock.sleep(600)
            wheel.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(8f)}
            compose.onNodeWithTag("time-minute").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(45f)}
            compose.onNodeWithContentDescription("切换时间输入").performClick()
            compose.onNodeWithText("分钟").assertTextContains("45")
            compose.onNodeWithText("小时").performTextReplacement("8")
            compose.waitUntil(10_000) { view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
            compose.onNodeWithText("完成").assertIsDisplayed(); capture("overlay-time-ime")
            shell("input keyevent 4")
            compose.waitUntil(10_000) { view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            assertNotNull(graph.pending.value)
            shell("input keyevent 4")
            compose.onNodeWithText("金额（元）").assertExists()
            compose.onNodeWithContentDescription("选择交易时间").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, originalTime))
            compose.onNodeWithText("18.01").assertExists(); capture("overlay-time-back")
        } finally {
            compose.runOnIdle { overlay?.close(); graph.discard(); graph.enable(oldEnabled); graph.activityVisible = true }
            shell("appops set io.github.localledger.app SYSTEM_ALERT_WINDOW $oldOp")
        }
    }
    @Test fun largeTextKeepsTheOverlayAndTimeEditorOperable() {
        ready()
        val oldFont = shell("settings get system font_scale")
        val oldOp = Regex("SYSTEM_ALERT_WINDOW: (\\w+)").find(shell("appops get io.github.localledger.app SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        val oldEnabled = graph.enabled.value
        var overlay: CandidateOverlay? = null
        try {
            shell("settings put system font_scale 2.0")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale == 2f }
            compose.runOnIdle { graph.setAppearance(Appearance.DARK) }
            capture("bills-dark-font200")
            click("统计"); capture("statistics-dark-font200")
            click("设置"); capture("settings-dark-font200")
            shell("appops set io.github.localledger.app SYSTEM_ALERT_WINDOW allow")
            compose.runOnIdle {
                compose.activity.setContent { Column(Modifier.fillMaxSize().background(Color(0xFF101010)).padding(24.dp)) {
                    Text("合成交易详情", color = Color.White)
                    Text("−18.00", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                } }
                graph.enable(true); graph.activityVisible = false
                graph.present(TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1800), "合成商户", "layout-large", "2026-10-31 12:00:00", "layout-v1", 1793419200000L))
            }
            compose.waitUntil(10_000) { graph.pending.value?.preparation != null }
            compose.runOnIdle { overlay = CandidateOverlay(app, graph, onFocusLost = { overlay?.close(); graph.discard() }); assertTrue(overlay!!.show()) }
            compose.onNodeWithText("保存").assertIsDisplayed()
            capture("overlay-dark-font200")
            compose.onNodeWithContentDescription("收起记账窗口").performClick()
            compose.waitUntil(10_000) { overlay?.expanded == false }
            capture("overlay-dark-collapsed-font200")
            compose.onNodeWithContentDescription("展开记账窗口").performClick()
            compose.waitUntil(10_000) { overlay?.expanded == true }
            compose.onNodeWithContentDescription("选择交易时间").assertIsDisplayed().performClick()
            capture("overlay-date-wheel-font200")
            compose.onNodeWithText("时间").performClick()
            capture("overlay-time-wheel-font200")
            compose.onNodeWithContentDescription("切换时间输入").performClick()
            compose.onNodeWithText("分钟").performTextReplacement("9")
            val view = CandidateOverlay::class.java.getDeclaredField("view").apply { isAccessible = true }.get(overlay) as android.view.View
            compose.waitUntil(10_000) { view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
            compose.onNodeWithText("完成").assertIsDisplayed()
            capture("overlay-time-ime-font200")
            shell("input keyevent 4")
            compose.waitUntil(10_000) { view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            shell("input keyevent 4")
            compose.onNodeWithText("保存").assertIsDisplayed()
            assertNotNull(graph.pending.value)
        } finally {
            compose.runOnIdle { overlay?.close(); graph.discard(); graph.enable(oldEnabled); graph.activityVisible = true }
            shell("appops set io.github.localledger.app SYSTEM_ALERT_WINDOW $oldOp")
            shell("settings put system font_scale $oldFont")
        }
    }

    @Test fun monthRangeAndNamedPresetsRemainUsableAtDoubleFont() {
        ready();val oldFont=shell("settings get system font_scale")
        try {
            compose.runOnIdle{graph.setAppearance(Appearance.LIGHT);graph.setQuery(LedgerQuery().inMonth(java.time.YearMonth.of(2026,10)))}
            shell("settings put system font_scale 2.0")
            compose.waitUntil(10_000){app.resources.configuration.fontScale==2f}
            compose.onNodeWithContentDescription("选择时间范围").performClick()
            compose.onNode(hasText("今年") and hasAnyAncestor(isDialog())).performScrollTo().performClick();ready()
            compose.onNodeWithContentDescription("选择时间范围").assert(hasText("今年"));capture("year-preset-font200")
            compose.onNodeWithContentDescription("选择时间范围").performClick();click("自定义")
            compose.onNodeWithContentDescription("选择开始月份").performClick();compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(2f)};click("确定")
            compose.onNodeWithContentDescription("选择结束月份").performClick();compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(1f)};click("确定")
            compose.onNodeWithText("结束月份需不早于开始月份").assertIsDisplayed()
            compose.onNodeWithText("应用").assertIsNotEnabled();capture("month-range-invalid-font200")
            compose.onNodeWithContentDescription("选择结束月份").performClick();compose.onNodeWithTag("month-number").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(10f)};click("确定")
            compose.onNodeWithText("应用").assertIsEnabled().performClick();ready()
            assertEquals(java.time.LocalDate.of(2026,2,1),graph.query.value.firstDate())
            assertEquals(java.time.LocalDate.of(2026,10,31),graph.query.value.lastDate());capture("month-range-applied-font200")
        } finally{shell("settings put system font_scale $oldFont")}
    }

}
