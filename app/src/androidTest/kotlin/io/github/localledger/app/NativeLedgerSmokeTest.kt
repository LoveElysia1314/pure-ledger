package io.github.localledger.app

import android.app.Application
import android.view.WindowInsets
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
import java.time.LocalDate

/** Instrumented synthetic checks. Never runs against a phone or an unnamed emulator. */
@RunWith(AndroidJUnit4::class)
class NativeLedgerSmokeTest {
    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
        }
        private lateinit var app: LedgerApplication
        private lateinit var databaseName: String
        @JvmStatic @BeforeClass fun isolateSyntheticData() {
            check(shell("getprop ro.boot.qemu.avd_name") == "LocalLedger_API35") { "Requires the isolated LocalLedger_API35 AVD; no phone data may be used." }
            app = instrumentation.targetContext.applicationContext as LedgerApplication
            AppStrings.initialize(chineseCopyContext(app))
            File(app.filesDir, "native-ui-screens").listFiles()?.filter { it.isFile && it.extension == "png" }?.forEach { check(it.delete()) }
            databaseName = "native-ui-${System.nanoTime()}.db"
            runBlocking(Dispatchers.IO) { LedgerStore(app, databaseName).use { store ->
                val account = store.saveAccount("现金",BigInteger("100000"))
                val now = System.currentTimeMillis()
                listOf("三餐" to 2600L,"交通" to 400L,"咖啡饮料" to 1800L,"买菜" to 3280L,"水果" to 1680L).forEachIndexed { index,(name,amount) ->
                    val category = store.snapshot().catalog.categories.single { it.name == name && it.type == TransactionType.EXPENSE }
                    store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(amount),"商户${index+1}",now-index*60_000,
                        note=if(index==0)"午餐" else "",categoryId=category.id,accountId=account))
                }
            } }
            // Test APK only. Replace the uninitialized app delegate without adding a production test API.
            app.javaClass.getDeclaredField("graph\$delegate").apply { isAccessible = true }.set(app, lazy { LedgerGraph(app,LedgerStore(app,databaseName)) })
        }
        @JvmStatic @AfterClass fun closeSyntheticGraph() {
            instrumentation.runOnMainSync { app.graph.close() }
            app.deleteDatabase(databaseName)
        }
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = app.graph
    private fun ready() = compose.waitUntil(20_000) { !graph.loading.value && !graph.busy.value && graph.snapshot.value.query==graph.query.value }
    private fun click(text:String) { val node=compose.onNodeWithText(text); if(!node.isDisplayed()) node.performScrollTo(); node.performClick() }
    private fun capture(name:String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(200) // Wait for the compositor after a configuration/inset change.
        val screenshot=instrumentation.uiAutomation.takeScreenshot() ?: error("No compositor screenshot")
        val output=File(app.filesDir,"native-ui-screens").apply { mkdirs() }
        File(output,"native-$name.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        screenshot.recycle()
    }
    @Before fun resetScope() {
        shell("wm user-rotation lock 0")
        compose.waitUntil(10_000) { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        ready(); compose.runOnIdle { graph.setAppearance(Appearance.LIGHT); graph.setQuery(LedgerQuery(bookId=DEFAULT_BOOK).inMonth(java.time.YearMonth.now())) }; ready()
        compose.runOnIdle { compose.activity.window.decorView.windowInsetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
    }
    @Test fun nativeKeyboardAndBackPreserveManualDraftAndSaveExactlyOnce() {
        val before=graph.snapshot.value.count
        capture("bills-light")
        compose.onNodeWithContentDescription("记一笔").performClick(); compose.showAmountKeypad(false)
        val keypadTop=compose.onNodeWithContentDescription("金额键盘").fetchSemanticsNode().boundsInRoot.top
        listOf("交易对方","余额变动").forEach { label ->
            val bounds=compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must fit above keypad",bounds.bottom<=keypadTop)
        }
        capture("entry-light")
        compose.onNodeWithContentDescription("输入1").performClick()
        compose.onNodeWithContentDescription("输入.").performClick()
        compose.onNodeWithContentDescription("输入2").performClick()
        click("三餐"); compose.onNodeWithContentDescription("交易对方").performScrollTo().performClick()
        compose.onNodeWithText("交易对方").performTextInput("NativeShop")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        compose.onNodeWithText("确定").assertIsDisplayed(); capture("merchant-ime")
        click("确定")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
        compose.waitUntil(10_000) { compose.onNodeWithText("保存").isDisplayed() }
        compose.onNodeWithText("NativeShop").performScrollTo().assertIsDisplayed(); compose.onNodeWithText("保存").assertIsDisplayed()
        repeat(2) { compose.onNodeWithText("金额（元）").performClick() }
        compose.waitForIdle()
        assertFalse(compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
        click("保存"); ready(); compose.waitUntil(10_000) { graph.snapshot.value.count==before+1 }
        val row=graph.snapshot.value.rows.single { it.merchant=="NativeShop" }
        assertEquals(120L,row.amount.minor)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { graph.setAppearance(Appearance.DARK) }; capture("bills-dark")
    }
    @Test fun realDateDialogAndAmountFiltersKeepApplyVisibleAboveTheIme() {
        compose.onNodeWithContentDescription("筛选").performClick()
        val lower=compose.onNodeWithText("最低金额"); if(!lower.isDisplayed())lower.performScrollTo()
        lower.performTextInput("10.00")
        compose.onNodeWithText("筛选").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsDisplayed(); capture("filter-ime")
        compose.onNodeWithText("应用").performClick(); ready()
        compose.waitUntil(20_000){graph.snapshot.value.query.minimumMinor==1000L}
        assertTrue(graph.snapshot.value.rows.all { it.amount.minor>=1000 })
        compose.onNodeWithContentDescription("清除筛选条件").assertIsDisplayed().performClick(); ready()
        compose.onNodeWithContentDescription("筛选").performClick()
        val start=compose.onNodeWithContentDescription("选择时间范围"); if(!start.isDisplayed())start.performScrollTo(); start.performClick()
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        compose.onNodeWithText("应用").assertIsDisplayed(); capture("filter-light")
    }
    @Test fun repeatEntryAndLandscapeRetainTheVisibleCategoryAndSaveControls() {
        val before = graph.snapshot.value.count
        val oldRotation = shell("settings get system user_rotation")
        val oldAuto = shell("settings get system accelerometer_rotation")
        try {
            compose.onNodeWithContentDescription("记一笔").performClick(); compose.showAmountKeypad(false)
            click("交通")
            compose.onNodeWithContentDescription("输入2").performClick()
            compose.onNodeWithContentDescription("搜索分类").performClick()
            compose.onNodeWithText("搜索分类").performTextInput("交通")
            compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            compose.waitUntil(10_000) { compose.onNodeWithText("收起键盘").isDisplayed() }
            compose.onNodeWithText("收起键盘").performClick()
            compose.waitUntil(10_000) { compose.onNodeWithContentDescription("保存并再记").isDisplayed() }
            compose.onNodeWithContentDescription("保存并再记").performClick(); ready()
            compose.waitUntil(10_000) { graph.snapshot.value.count == before + 1 }
            compose.showAmountKeypad(false)
            compose.onNodeWithText("0.00").assertIsDisplayed()
            compose.onAllNodesWithText("搜索分类").assertCountEquals(0)
            compose.onNodeWithText("三餐").assertIsDisplayed()
            shell("wm user-rotation lock 1")
            compose.waitUntil(10_000) { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText("三餐").assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            val category = compose.onNodeWithText("三餐").fetchSemanticsNode().boundsInRoot
            val keyboard = compose.onNodeWithContentDescription("金额键盘").fetchSemanticsNode().boundsInRoot
            assertTrue(category.bottom <= keyboard.top)
            capture("entry-portrait-rotation-lock")
            compose.onNodeWithContentDescription("输入3").performClick(); click("保存"); ready()
            compose.waitUntil(10_000) { graph.snapshot.value.count == before + 2 }
        } finally {
            shell("wm user-rotation lock $oldRotation"); if (oldAuto == "1") shell("wm user-rotation free")
        }
    }
    @Test fun categorySearchUsesOneKeyboardAndAutomaticSettingsHaveWorkingBackNavigation() {
        click("设置"); capture("settings-light")
        click("自动记账")
        compose.onNodeWithText("无障碍").assertIsDisplayed(); compose.onNodeWithText("悬浮窗").assertIsDisplayed()
        capture("automatic-light")
        compose.onNodeWithContentDescription("返回").performClick(); click("账单")
        compose.onNodeWithContentDescription("记一笔").performClick(); compose.showAmountKeypad(false)
        compose.onNodeWithContentDescription("搜索分类").performClick()
        compose.onNodeWithText("搜索分类").performTextInput("交通")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        compose.onNode(hasText("交通") and isSelectable()).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("金额键盘").assertCountEquals(0)
        capture("category-ime")
        compose.onNode(hasText("交通") and isSelectable()).performClick()
        compose.waitUntil(10_000) { compose.onNodeWithContentDescription("输入8").isDisplayed() }
        compose.onAllNodesWithText("搜索分类").assertCountEquals(0)
        compose.onNodeWithContentDescription("搜索分类").performClick()
        compose.onNodeWithText("搜索分类").performTextInput("交通")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        click("收起键盘")
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
        compose.waitUntil(10_000) { compose.onNodeWithContentDescription("输入8").isDisplayed() }
        compose.onNodeWithContentDescription("输入8").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
    }
    @Test fun systemDarkModeAndDoubleFontKeepLongMoneyAndActionsReachable() {
        val oldFont = shell("settings get system font_scale").takeUnless { it == "null" } ?: "1.0"
        val oldNight = shell("cmd uimode night").substringAfter(": ").trim()
        val oldRotation = shell("settings get system user_rotation")
        val oldAuto = shell("settings get system accelerometer_rotation")
        try {
            compose.runOnIdle { graph.setAppearance(Appearance.SYSTEM) }
            shell("cmd uimode night yes"); shell("settings put system font_scale 2.0")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.9f &&
                compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }
            compose.onNodeWithContentDescription("记一笔").performClick(); compose.showAmountKeypad(false)
            repeat(2) { compose.onNodeWithText("金额（元）").performClick() }
            "92233720368547758.07".forEach { compose.onNodeWithContentDescription("输入$it").performClick() }
            assertFalse(compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
            capture("entry-system-dark-font200-before-assert")
            compose.onNodeWithText("保存").assertIsDisplayed(); compose.onNodeWithText("三餐").assertIsDisplayed()
            capture("entry-system-dark-font200")
            shell("wm user-rotation lock 1")
            compose.waitUntil(10_000) { app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText("保存").assertIsDisplayed(); compose.onNodeWithText("三餐").assertIsDisplayed()
            capture("entry-portrait-rotation-font200")
            compose.onNodeWithContentDescription("输入9").performScrollTo().assertIsDisplayed()
            // This option has a horizontal scroll parent; use the user's vertical gesture
            // on the containing information pane instead of a test-only nested scroll request.
            repeat(2) {
                if (!compose.onNodeWithContentDescription("选择交易时间").isDisplayed())
                    compose.onNodeWithContentDescription("记账信息").performTouchInput { swipeUp() }
            }
            compose.onNodeWithContentDescription("选择交易时间").assertIsDisplayed()
            compose.onNodeWithText("保存").assertIsDisplayed()
            capture("entry-portrait-rotation-font200-scrolled")
        } finally {
            shell("wm user-rotation lock $oldRotation"); if (oldAuto == "1") shell("wm user-rotation free")
            shell("settings put system font_scale $oldFont"); shell("cmd uimode night ${oldNight.takeIf { it in setOf("yes", "no", "auto", "custom") } ?: "no"}")
        }
    }
}
