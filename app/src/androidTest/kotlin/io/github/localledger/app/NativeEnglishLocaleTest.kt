package io.github.localledger.app

import android.app.LocaleManager
import android.os.LocaleList
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.data.*
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
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Real Android per-app locales, IME and overlay rendering with disposable synthetic data. */
@RunWith(AndroidJUnit4::class)
class NativeEnglishLocaleTest {
    companion object {
        private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
        private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r->r.readText().trim() }
        }
        private lateinit var app:LedgerApplication
        private lateinit var database:String
        private lateinit var originalLocales:LocaleList
        private lateinit var book:String
        private lateinit var wechat:String
        private lateinit var alipay:String
        @JvmStatic @BeforeClass fun seed() {
            check(shell("getprop ro.boot.qemu.avd_name")=="LocalLedger_API35")
            app=instrumentation.targetContext.applicationContext as LedgerApplication
            originalLocales=app.getSystemService(LocaleManager::class.java).applicationLocales
            instrumentation.runOnMainSync { app.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags("en") }
            val deadline=android.os.SystemClock.uptimeMillis()+10_000
            while(app.resources.configuration.locales[0].language!="en") { check(android.os.SystemClock.uptimeMillis()<deadline);android.os.SystemClock.sleep(50) }
            AppStrings.initialize(app)
            database="english-${System.nanoTime()}.db"
            File(app.filesDir,"english-review").listFiles()?.filter { it.isFile && it.extension=="png" }?.forEach { check(it.delete()) }
            runBlocking(Dispatchers.IO) { LedgerStore(app,database).use { store ->
                book=store.saveBook("旅行账本")
                val account=store.saveAccount("微信钱包",BigInteger("100000"))
                wechat=store.saveAccount("WeChat",BigInteger("200000"));alipay=store.saveAccount("Alipay",BigInteger("300000"))
                val category=store.saveCategory(TransactionType.EXPENSE,"早餐 Breakfast")
                store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(1834),"合成商户",System.currentTimeMillis(),"早餐 note",book,category,account),"english-history")
            } }
            app.javaClass.getDeclaredField("graph\$delegate").apply { isAccessible=true }.set(app,lazy { LedgerGraph(app,LedgerStore(app,database)) })
        }
        @JvmStatic @AfterClass fun close() {
            if (::app.isInitialized) instrumentation.runOnMainSync {
                app.graph.close()
                if (::originalLocales.isInitialized) app.getSystemService(LocaleManager::class.java).applicationLocales=originalLocales
                AppStrings.initialize(app)
            }
            if (::app.isInitialized && ::database.isInitialized) app.deleteDatabase(database)
        }
    }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val graph get()=app.graph
    private fun ready()=compose.waitUntil(15_000){!graph.loading.value && !graph.busy.value}
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());if(!node.isDisplayed())node.performScrollTo();node.performClick() }
    private fun locale(tag:String) {
        compose.runOnIdle { app.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags(tag) }
        compose.waitUntil(15_000){app.resources.configuration.locales[0].language==tag}
        ready()
    }
    @Before fun english() { locale("en");compose.runOnIdle { AppStrings.initialize(app);graph.setCategoryDisplay(CategoryDisplay.AUTO);graph.setAppearance(Appearance.DARK);graph.setQuery(LedgerQuery(bookId=book).inMonth(java.time.YearMonth.now())) };ready() }
    private fun capture(name:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(250)
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(app.filesDir,"english-review").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun categoryDisplayPersistsAndAutomaticFollowsTheLanguageWithoutChangingData() {
        val before=graph.snapshot.value.catalog
        click("Settings");click("Category layout");click("Grid")
        assertEquals(CategoryDisplay.GRID,graph.categoryDisplay.value)
        compose.activityRule.scenario.recreate();ready()
        click("Settings");click("Category")
        compose.onNodeWithTag("category-layout-grid").assertExists();capture("categories-grid-english")
        compose.onNodeWithContentDescription("Back").performClick();click("Category layout");click("List");click("Category")
        compose.onNodeWithTag("category-layout-list").assertExists();capture("categories-list-english")
        compose.onNodeWithContentDescription("Back").performClick();click("Category layout");click("Automatic")
        click("Category");compose.onNodeWithTag("category-layout-list").assertExists()
        locale("zh");compose.onNodeWithTag("category-layout-grid").assertExists();capture("categories-auto-chinese")
        assertEquals(before,graph.snapshot.value.catalog)
    }
    @Test fun languageSwitchRecreatesTheUiWithoutRenamingOrRewritingSavedEntries() {
        val before=graph.snapshot.value
        compose.onNodeWithText("旅行账本").assertIsDisplayed();capture("bills-english-dark")
        click("Stats");capture("statistics-english-dark")
        click("Settings");compose.onNodeWithText("Language").assertExists();capture("settings-english-dark")
        assertEquals(AppLanguage.entries.map{it.tag}.toSet(),android.app.LocaleConfig(app).supportedLocales!!.let { list -> (0 until list.size()).map{list[it].toLanguageTag()}.toSet() })
        click("Language")
        for(label in listOf("System default","简体中文","繁體中文","English","日本語"))
            compose.onNode(hasText(label) and hasAnyAncestor(isDialog()) and hasClickAction()).assertIsDisplayed()
        capture("language-picker")
        fun choose(label:String,tag:String) {
            compose.onNode(hasText(label) and hasAnyAncestor(isDialog()) and hasClickAction()).performClick()
            compose.waitUntil(15_000){app.resources.configuration.locales[0].toLanguageTag()==tag};ready()
        }
        choose("繁體中文","zh-Hant")
        compose.onNode(hasText("設定") and hasClickAction()).assertIsDisplayed();capture("settings-traditional")
        click("語言");choose("日本語","ja")
        compose.onNode(hasText("設定") and hasClickAction()).assertIsDisplayed();compose.onNodeWithText("言語").assertExists();capture("settings-japanese")
        click("言語");choose("简体中文","zh-Hans")
        compose.onNode(hasText("设置") and hasClickAction()).assertIsDisplayed();compose.onNodeWithText("语言").assertExists();capture("settings-chinese-after-switch")
        assertEquals(before.rows,graph.snapshot.value.rows)
        assertEquals(before.catalog,graph.snapshot.value.catalog)
        click("语言");choose("English","en")
        compose.onNode(hasText("Settings") and hasClickAction()).assertIsDisplayed()
        click("Category");compose.onNodeWithText("旅行账本").assertDoesNotExist()
        compose.onNodeWithContentDescription("Category arrangement").assertExists();capture("categories-english-dark")
        assertTrue(graph.snapshot.value.catalog.categories.any { it.name=="早餐 Breakfast" })
        compose.onNodeWithContentDescription("Back").performClick();click("Entries")
        compose.onNodeWithContentDescription("Add entry").performClick(); compose.showAmountKeypad(true)
        "12.34".forEach { compose.onNodeWithContentDescription("Enter $it").performClick() }
        locale("zh");compose.onNodeWithText("12.34").assertIsDisplayed();compose.onNodeWithText("保存").assertIsDisplayed()
        locale("en");compose.onNodeWithText("12.34").assertIsDisplayed();compose.onNodeWithText("Save").assertIsDisplayed()
        capture("draft-after-language-switch")
        compose.onNodeWithContentDescription("Back").performClick();click("Discard")
        assertEquals(before.rows,graph.snapshot.value.rows)
    }
    @Test fun englishWeChatAndAlipayCandidatesUseRealOverlayControlsAndSaveExactCnyAmounts() {
        val oldOp=Regex("SYSTEM_ALERT_WINDOW: (\\w+)").find(shell("appops get io.github.localledger.app SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        val enabled=graph.enabled.value
        var overlay:CandidateOverlay?=null
        val displayed=LocalDateTime.now().withNano(0).format(DateTimeFormatter.ofPattern("MMM d, uuuu HH:mm:ss",Locale.ENGLISH))
        val parser=PaymentDetailParser()
        val pages=listOf(
            PageSnapshot("com.tencent.mm",listOf("Transaction Details","Synthetic merchant","-CNY 18.34","Payment Successful","Payment Time",displayed,"Transaction ID","NATIVE-EN-WX-0001"),0),
            PageSnapshot("com.eg.android.AlipayGphone",listOf("Bill Details","Synthetic merchant","支出14.20元","Waiting confirmation of receipt","Discount","-0.20","Payment Time",LocalDateTime.now().withNano(0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))),0))
        val before=graph.snapshot.value.count
        try {
            shell("appops set io.github.localledger.app SYSTEM_ALERT_WINDOW allow")
            compose.runOnIdle { compose.activity.setContent { Column(Modifier.fillMaxSize().background(Color(0xFF101010)).padding(24.dp)) { Text("Transaction details",color=Color.White);Text("Synthetic merchant",color=Color.White);Text("CNY 18.34",color=Color.White) } };graph.enable(true);graph.activityVisible=false }
            pages.forEachIndexed { index,page ->
                val source=(parser.parse(page) as RecognitionResult.Recognized).candidate
                compose.runOnIdle { graph.present(source) }
                compose.waitUntil(10_000){graph.pending.value?.preparation!=null}
                assertEquals(if(index==0)wechat else alipay,graph.pending.value!!.preparation!!.draft.accountId)
                compose.runOnIdle { overlay=CandidateOverlay(app,graph,onFocusLost={overlay?.close();graph.discard()});assertTrue(overlay!!.show()) }
                compose.onNodeWithText("Pure Ledger").assertIsDisplayed();compose.onNodeWithText("Save").assertIsDisplayed()
                capture(if(index==0)"wechat-overlay-english" else "alipay-overlay-english")
                if(index==0) {
                    val merchantTag=compose.onNodeWithContentDescription("Counterparty").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val save=compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
                    assertTrue(merchantTag.bottom<=save.top);capture("overlay-tags-complete")
                    listOf("Choose transaction time","Account","Ledger","Counterparty").forEach { label ->
                        val bounds=compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
                        assertTrue("$label touch target",bounds.width>=48*app.resources.displayMetrics.density && bounds.height>=48*app.resources.displayMetrics.density)
                    }
                    compose.onNodeWithText("Amount (CNY)").performTextReplacement("18.35")
                    val view=CandidateOverlay::class.java.getDeclaredField("view").apply{isAccessible=true}.get(overlay) as android.view.View
                    assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,(view.layoutParams as WindowManager.LayoutParams).type)
                    compose.waitUntil(10_000){view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
                    capture("overlay-english-ime")
                    compose.onNodeWithContentDescription("Choose transaction time").assertIsDisplayed().performClick()
                    click("Time");capture("time-picker-english");click("Cancel")
                    compose.onNodeWithContentDescription("Account").assertIsDisplayed().performClick()
                    compose.onNodeWithText("微信钱包").assertExists();click("WeChat")
                }
                click("Save");compose.waitUntil(10_000){graph.pending.value==null};ready()
                compose.waitUntil(10_000){graph.snapshot.value.count==before+index+1}
            }
            assertTrue(graph.snapshot.value.rows.any { it.source=="RECOGNIZED" && it.amount.minor==1835L && it.accountId==wechat })
            assertTrue(graph.snapshot.value.rows.any { it.source=="RECOGNIZED" && it.amount.minor==1420L && it.accountId==alipay })
        } finally {
            compose.runOnIdle { overlay?.close();graph.discard();graph.enable(enabled);graph.activityVisible=true }
            shell("appops set io.github.localledger.app SYSTEM_ALERT_WINDOW $oldOp")
        }
    }
    @Test fun manualNoteStartsFocusedAndAmountSwitchesToTheBuiltInKeypad() {
        val before=graph.snapshot.value.count
        compose.onNodeWithContentDescription("Add entry").performClick()
        compose.onNodeWithContentDescription("Note").assertIsFocused().performTextReplacement("Lunch note directly entered")
        compose.waitUntil(10_000){compose.activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        compose.onNodeWithText("Save").assertIsDisplayed()
        val actionTop=compose.onNodeWithText("Hide keypad").fetchSemanticsNode().boundsInRoot.top
        listOf("Counterparty","Balance change").forEach { label ->
            val bounds=compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must fit above IME actions",bounds.bottom<=actionTop)
        }
        capture("entry-note-ime")
        compose.showAmountKeypad(true)
        "12.34".forEach { compose.onNodeWithContentDescription("Enter $it").performClick() }
        compose.onNodeWithContentDescription("Note").assertTextContains("Lunch note directly entered")
        compose.onNodeWithText("Uncategorized").assertDoesNotExist()
        capture("entry-note-keypad")
        click("Save");ready();compose.waitUntil(10_000){graph.snapshot.value.count==before+1}
        assertTrue(graph.snapshot.value.rows.any { it.note=="Lunch note directly entered" && it.amount.minor==1234L && it.categoryId!=null })
    }

    @Test fun periodTrendDrilldownAndExportUseSharedDatesAndSemanticSuccessFeedback() {
        compose.runOnIdle { graph.setQuery(graph.query.value.inPreset(DateRangePreset.ALL)) };ready()
        click("Stats");click("Over time")
        compose.onNodeWithContentDescription("Cash flow over time").performScrollTo().assertIsDisplayed()
        capture("trend-chart-english-dark")
        compose.onNodeWithContentDescription("List").performClick();capture("trend-list-english-dark")
        compose.onNodeWithContentDescription("Chart").performClick();click("Month entries");ready()
        assertNotNull(graph.query.value.firstDate());assertNotNull(graph.query.value.lastDate())
        capture("trend-period-entries")
        compose.onNodeWithContentDescription("Date range").performClick();click("Last month");ready()
        assertEquals(java.time.YearMonth.now().minusMonths(1).atDay(1),graph.query.value.firstDate())
        assertEquals(java.time.YearMonth.now().minusMonths(1).atEndOfMonth(),graph.query.value.lastDate())
        val chosen=graph.query.value
        compose.onNodeWithContentDescription("Date range").performClick();click("Custom");click("Cancel")
        assertEquals(chosen,graph.query.value)
        val output=File(app.cacheDir,"synthetic-native-export.zip")
        try {
            compose.runOnIdle { graph.exportTo(android.net.Uri.fromFile(output)) };ready()
            compose.waitUntil(10_000){graph.notice.value?.kind==NoticeKind.SUCCESS}
            compose.onNodeWithText("Export complete").assertIsDisplayed();capture("export-success-green")
            assertTrue(output.length()>0)
            compose.runOnIdle { graph.setAppearance(Appearance.LIGHT);graph.exportTo(android.net.Uri.fromFile(output)) };ready()
            compose.waitUntil(10_000){graph.notice.value?.kind==NoticeKind.SUCCESS}
            compose.onNodeWithText("Export complete").assertIsDisplayed();capture("export-success-light")
        } finally { output.delete() }
    }

    @Test fun englishOn320DpAndDoubleFontKeepsLongMoneyAndSaveReachable() {
        val oldFont=shell("settings get system font_scale")
        val oldSize=Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1)
        try {
            shell("wm size 960x2220");shell("settings put system font_scale 2.0")
            compose.waitUntil(15_000){app.resources.configuration.screenWidthDp==320 && app.resources.configuration.fontScale==2f};ready()
            compose.onNodeWithContentDescription("Add entry").performClick(); compose.showAmountKeypad(true)
            compose.onNodeWithContentDescription("Choose entry type").performClick();click("Transfer")
            compose.onNodeWithText("From account").assertExists()
            compose.onNodeWithContentDescription("Choose entry type").performClick();click("Expense")
            val amount="92233720368547758.07"
            amount.forEach { val key=compose.onNodeWithContentDescription("Enter $it");if(!key.isDisplayed())key.performScrollTo();key.performClick() }
            compose.onNodeWithText(amount).assertIsDisplayed();compose.onNodeWithContentDescription("Save").assertIsDisplayed()
            compose.onNodeWithContentDescription("Save and add another").assertIsDisplayed();capture("entry-english-320-font200")
            compose.onNodeWithContentDescription("Back").performClick();click("Discard")
            click("Settings");capture("settings-english-320-font200")
        } finally { shell("wm size "+(oldSize ?: "reset"));shell("settings put system font_scale $oldFont") }
    }
}
