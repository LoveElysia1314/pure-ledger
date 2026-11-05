package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.localledger.app.data.*
import io.github.localledger.app.overlay.CandidateOverlay
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
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import java.io.File
import org.json.JSONObject
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayContrastTest {
    @org.junit.Before fun useAllTimeForHistoricalFixtures() {
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
        compose.runOnIdle { graph.setQuery(io.github.localledger.core.LedgerQuery()) }
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    private var overlay: CandidateOverlay? = null
    private var sourceDark by mutableStateOf(false)
    private val candidate = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1800),
        "合成商户", "synthetic-overlay-contrast", "2026-10-31 12:00:00", "v1", 1793419200000)
    @After fun close() {
        compose.runOnUiThread { overlay?.close(); graph.enable(false); graph.close() }
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    private fun show(dark: Boolean) {
        ready()
        compose.runOnUiThread {
            graph.setAppearance(if (dark) Appearance.SYSTEM else Appearance.LIGHT)
            sourceDark = dark
            // Synthetic source UI only. The overlay itself is the production WindowManager view.
            compose.activity.setContent {
                val bg = if (sourceDark) Color(0xFF101010) else Color.White
                val ink = if (sourceDark) Color.White else Color(0xFF111111)
                Column(Modifier.fillMaxSize().background(bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    Text("交易详情", color = ink, style = MaterialTheme.typography.titleLarge)
                    Text("合成商户", color = ink); Text("−18.00", color = ink, style = MaterialTheme.typography.headlineLarge)
                    Text("交易成功", color = ink); Text("支付时间 2026-10-31 12:00:00", color = ink)
                }
            }
            ShadowSettings.setCanDrawOverlays(true)
            graph.enable(true); graph.activityVisible = false; graph.present(candidate)
        }
        compose.waitUntil(10_000) { graph.pending.value?.preparation != null }
        compose.runOnUiThread { overlay = CandidateOverlay(compose.activity.applicationContext, graph); assertTrue(overlay!!.show()) }
        compose.onNodeWithText("纯净记账").assertIsDisplayed()
        // Model the focus notification from WindowManager for a newly attached overlay.
        // Robolectric's application-overlay windows do not deliver it consistently.
        compose.runOnUiThread { root().dispatchWindowFocusChanged(true) }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(220))
        compose.waitForIdle()
        val params = root().layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
        assertEquals(.12f, params.dimAmount, .0001f)
        assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, params.gravity)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val accent = android.util.TypedValue()
        assertTrue(root().context.theme.resolveAttribute(android.R.attr.colorAccent, accent, true))
        assertEquals(root().resources.getColor(if(dark)R.color.ledger_primary_dark else R.color.ledger_primary_light,null), accent.data)
    }
    @Test fun lightOverlaySeparatesBothSourceThemesAndCancelRemovesTheDimmedWindow() {
        show(false); capture("contrast-overlay-light-on-light")
        compose.runOnUiThread { sourceDark = true }; compose.waitForIdle(); capture("contrast-overlay-light-on-dark")
        val before = graph.pending.value!!
        val originalHeight = root().height
        compose.onNodeWithText("金额（元）").performTextReplacement("18.01")
        compose.onNodeWithContentDescription("交易对方").assertIsDisplayed().performClick()
        compose.onNodeWithText("交易对方").performTextReplacement("已修改商户")
        compose.onNodeWithText("备注").performTextInput("保留备注")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("三餐").assertIsDisplayed().performClick()
        compose.onNode(hasText("三餐") and isSelectable()).assertIsSelected()
        capture("contrast-before-collapse")
        compose.onNodeWithContentDescription("收起记账窗口").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { overlay?.expanded == false }
        compose.onNodeWithText("− ¥ 18.01").assertIsDisplayed()
        assertTrue(root().height < originalHeight)
        val compact = root().layoutParams as WindowManager.LayoutParams
        assertTrue(compact.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(0, compact.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(0f, compact.dimAmount, 0f)
        capture("contrast-overlay-collapsed")
        compose.onNodeWithContentDescription("展开记账窗口").performClick()
        compose.waitUntil(10_000) { overlay?.expanded == true }
        compose.onNodeWithText("金额（元）").assertTextContains("18.01")
        compose.onNodeWithText("已修改商户").assertExists()
        compose.onNodeWithContentDescription("备注").assertTextContains("保留备注")
        compose.onNode(hasText("三餐") and isSelectable()).assertIsSelected()
        assertEquals(before.token, graph.pending.value!!.token)
        assertEquals(before.requestId, graph.pending.value!!.requestId)
        capture("contrast-overlay-restored")
        val attached = root()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { graph.pending.value == null && overlay?.visible == false }
        assertFalse(attached.isAttachedToWindow); assertEquals(0, graph.snapshot.value.count)
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h740dp-night-mdpi")
    fun darkLargeTextOverlayKeepsNormalRepeatedSaveAndReleasesItsWindow() {
        RuntimeEnvironment.setFontScale(2f); compose.activityRule.scenario.recreate(); ready()
        val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(context).use { it.saveCandidate(ConfirmedEntry(candidate), "synthetic-first-request") } }
        compose.runOnUiThread { graph.refresh() }; ready()
        show(true)
        compose.onNodeWithText("重复").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起记账窗口").performClick()
        compose.onNodeWithText("重复").assertIsDisplayed()
        capture("duplicate-dark-collapsed-font200")
        compose.onNodeWithContentDescription("展开记账窗口").performClick()
        compose.onNodeWithText("保存").assertIsDisplayed()
        capture("contrast-overlay-dark-on-dark-font200")
        compose.runOnUiThread { sourceDark = false }; compose.waitForIdle(); capture("contrast-overlay-dark-on-light-font200")
        val attached = root()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { graph.pending.value == null && graph.snapshot.value.count == 2 && overlay?.visible == false }
        assertFalse(attached.isAttachedToWindow)
    }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h800dp-night-xxhdpi")
    fun followsSystemDarkAndRestoresTheBottomWindowFromItsSummary() {
        show(true)
        listOf("金额（元）", "保存").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf("账本", "账户", "备注", "选择交易时间", "交易对方").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("分类目录").assertIsDisplayed()
        compose.onNodeWithText("水果").assertIsDisplayed()
        compose.onNodeWithText("通讯").assertIsDisplayed()
        compose.onNodeWithContentDescription("全部分类").assertDoesNotExist()
        assertTrue(root().height / root().resources.displayMetrics.density <= 576f)
        assertTouchTarget(compose.onNodeWithText("金额（元）"))
        listOf("交易对方", "备注").forEach {
            val node=compose.onNodeWithContentDescription(it);if(!node.isDisplayed())node.performScrollTo();assertTouchTarget(node)
        }
        listOf("支出", "收入", "三餐", "保存", "取消").forEach { assertTouchTarget(compose.onNode(hasText(it) and hasClickAction())) }
        listOf("账本", "账户").forEach { assertTouchTarget(compose.onNodeWithContentDescription(it).assertIsDisplayed()) }
        assertTouchTarget(compose.onNodeWithContentDescription("收起记账窗口"))
        assertTouchTarget(compose.onNodeWithContentDescription("搜索分类"))
        capture("contrast-overlay-dark-on-dark")
        compose.runOnUiThread { sourceDark = false }; compose.waitForIdle(); capture("contrast-overlay-dark-on-light")
        val expandedHeight = root().height
        compose.onNodeWithContentDescription("收起记账窗口").performClick()
        compose.waitUntil(10_000) { overlay?.expanded == false }
        compose.onNodeWithContentDescription("展开记账窗口").assertIsDisplayed()
        compose.waitUntil(10_000) { root().height < expandedHeight }; capture("contrast-overlay-dark-collapsed")
        compose.onNodeWithContentDescription("展开记账窗口").performClick()
        compose.waitUntil(10_000) { overlay?.expanded == true }
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(10_000) { overlay?.visible == false && graph.pending.value == null }
        assertEquals(0, graph.snapshot.value.count)
    }
    @Test @Config(sdk = [34], qualifiers = "w360dp-h800dp-night-xxhdpi")
    fun compactOptionsExposePostingPolicyAndUseTheSameSaveTransaction() {
        ready(); val context = compose.activity.applicationContext
        lateinit var account: String
        runBlocking(Dispatchers.IO) { LedgerStore(context).use {
            account = it.saveAccount("微信", BigInteger("10000"))
            it.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1), "此前账目", 1, accountId = account, affectsBalance = false))
        } }
        compose.runOnUiThread { graph.refresh() }; ready(); show(true)
        compose.onNodeWithText("余额不变", substring = true).assertIsDisplayed()
        compose.onNodeWithText("计入账户余额").assertDoesNotExist()
        compose.onNodeWithContentDescription("账户").performClick()
        compose.onNodeWithText("计入账户余额").performClick()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("余额不变", substring = true).assertDoesNotExist()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { graph.pending.value == null && graph.snapshot.value.count == 2 }
        val row = graph.snapshot.value.rows.first { it.source == "RECOGNIZED" }
        assertTrue(row.affectsBalance); assertEquals(account, row.accountId)
        assertEquals(BigInteger("8200"), graph.snapshot.value.catalog.accounts.single { it.id==account }.balanceMinor)
    }
    @Test fun compactSearchFindsOtherCategoriesAndInvalidMoneyDoesNotWrite() {
        show(false)
        compose.onNodeWithText("金额（元）").performTextReplacement("18.001")
        compose.onNodeWithText("保存").performClick(); compose.waitForIdle()
        assertEquals(0, graph.snapshot.value.count); assertNotNull(graph.pending.value)
        compose.onNodeWithText("金额（元）").performTextReplacement("18.25")
        compose.onNodeWithContentDescription("搜索分类").performClick()
        compose.onNodeWithText("搜索分类").performScrollTo().performTextInput("宠物")
        compose.onNode(hasText("宠物") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("结束分类搜索").assertDoesNotExist()
        compose.onNodeWithContentDescription("分类目录").performScrollToNode(hasText("宠物"))
        compose.onNode(hasText("宠物") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { graph.pending.value == null && graph.snapshot.value.count == 1 }
        val row = graph.snapshot.value.rows.single()
        assertEquals(1825L, row.amount.minor)
        assertEquals("宠物", graph.snapshot.value.catalog.categories.single { it.id == row.categoryId }.name)
    }
    @Test fun entireCategoryCatalogCanBeChosenWithoutExpandingAndTypeChangesKeepChoices() {
        show(false)
        compose.onNodeWithText("搜索分类").assertDoesNotExist()
        compose.onNodeWithContentDescription("分类目录").performScrollToNode(hasText("宠物"))
        compose.onNode(hasText("宠物") and isSelectable()).performClick().assertIsSelected()
        compose.onNodeWithText("收入").performClick()
        compose.onNodeWithContentDescription("分类目录").performScrollToNode(hasText("工资"))
        compose.onNode(hasText("工资") and isSelectable()).performClick().assertIsSelected()
        compose.onNodeWithText("支出").performClick()
        compose.onNodeWithContentDescription("分类目录").performScrollToNode(hasText("宠物"))
        compose.onNode(hasText("宠物") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("搜索分类").assertDoesNotExist()
        assertEquals(0, graph.snapshot.value.count)
        capture("compact-category-browsing")
    }
    private fun assertTouchTarget(node: SemanticsNodeInteraction) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = root().resources.displayMetrics.density
        assertTrue("Touch target $bounds must be at least 48dp", bounds.width / density >= 47.5f && bounds.height / density >= 47.5f)
    }
    @Test @Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-night-xxhdpi")
    fun inlineTimeChangesSurviveCollapseAndSaveWithoutChangingSourceEvidence() {
        show(true)
        val before = graph.pending.value!!
        compose.onNodeWithContentDescription("备注").performTextInput("保留备注")
        compose.onNodeWithContentDescription("选择交易时间").assertIsDisplayed().performClick()
        compose.onNodeWithText("日期").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换日期输入").performClick()
        compose.onNode(hasSetTextAction() and hasText("日期")).performTextReplacement("2026-11-01")
        compose.onNodeWithText("时间").performClick()
        compose.onNodeWithContentDescription("切换时间输入").performClick()
        compose.onNodeWithText("小时").performTextReplacement("24")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("小时需为 0–23").assertExists()
        assertEquals(0, graph.snapshot.value.count)
        compose.onNodeWithText("小时").performTextReplacement("09")
        compose.onNodeWithText("分钟").performTextReplacement("45")
        capture("completion-time-editor-dark")
        compose.onNodeWithContentDescription("收起记账窗口").performClick()
        compose.onNodeWithContentDescription("展开记账窗口").performClick()
        compose.onNodeWithText("分钟").assertTextContains("45")
        compose.onNodeWithText("完成").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("备注").assertTextContains("保留备注")
        compose.onNodeWithContentDescription("选择交易时间").assert(SemanticsMatcher("edited time") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { "" }.startsWith("2026-11-01 09:45:")
        })
        assertEquals(before.requestId, graph.pending.value!!.requestId)
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 && graph.pending.value == null }
        val row = graph.snapshot.value.rows.single()
        val edited = java.time.Instant.ofEpochMilli(row.occurredAtMs).atZone(java.time.ZoneId.systemDefault())
        assertEquals(java.time.LocalDate.of(2026,11,1), edited.toLocalDate()); assertEquals(9,edited.hour); assertEquals(45,edited.minute)
        val appContext = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) { LedgerStore(appContext).use { store ->
            store.readableDatabase.rawQuery("SELECT source_time,evidence_hash FROM recognition_receipts", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(candidate.occurredAtMs!!,c.getLong(0)); assertEquals(candidate.evidenceHash(),c.getString(1))
            }
        } }
    }
    @Test @Config(sdk = [34], qualifiers = "en-rUS-w320dp-h740dp-night-mdpi")
    fun inlineTimeBackCancelsThePickerRatherThanTheCandidateAtLargeFont() {
        RuntimeEnvironment.setFontScale(2f); compose.activityRule.scenario.recreate(); ready(); show(true)
        compose.onNodeWithContentDescription("选择交易时间").assertIsDisplayed().performClick()
        compose.onNodeWithText("完成").assertIsDisplayed()
        capture("completion-time-editor-font200")
        val owner = ReflectionHelpers.getField<androidx.activity.OnBackPressedDispatcherOwner>(overlay, "owner")
        compose.runOnIdle { owner.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("保存").assertIsDisplayed()
        assertNotNull(graph.pending.value); assertEquals(0, graph.snapshot.value.count)
        compose.onNodeWithText("取消").performClick()
    }
    private fun root(): View = ReflectionHelpers.getField<View>(overlay, "view")
    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val base = compose.activity.window.decorView
            val panel = root(); val params = panel.layoutParams as WindowManager.LayoutParams
            assertTrue(panel.width > 0 && panel.height > 0)
            bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); base.draw(canvas)
            // Robolectric does not run SurfaceFlinger. Composite the real window view using its
            // real dimAmount/gravity for a local illustration, not a device/compositor screenshot.
            if (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
            assertTrue(panel.height <= base.height * .82f)
            canvas.save(); canvas.translate((base.width - panel.width) / 2f, (base.height - panel.height - params.y).toFloat())
            panel.draw(canvas); canvas.restore()
            File("build/ui-previews", "$name.json").writeText(JSONObject().put("width_dp", panel.width / panel.resources.displayMetrics.density)
                .put("height_dp", panel.height / panel.resources.displayMetrics.density).put("font_scale", panel.resources.configuration.fontScale)
                .put("expanded", overlay?.expanded).toString(2))
        }
        File("build/ui-previews", "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
