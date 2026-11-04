package io.github.localledger.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.core.*
import io.github.localledger.app.data.LedgerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import io.github.localledger.app.accessibility.LedgerAccessibilityService
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedgerUiTest {
    @org.junit.Before fun useAllTimeForHistoricalFixtures() {
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
        compose.runOnIdle { graph.setQuery(io.github.localledger.core.LedgerQuery()) }
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    @After fun close() { compose.runOnUiThread { graph.enable(false); graph.dismissOverlay?.invoke(); graph.close() } }

    @Test fun navigationEmptyStateAndSettings() {
        compose.waitUntil(10_000) { !graph.loading.value }
        compose.onNodeWithText("暂无账单").assertIsDisplayed()
        capture("ledger-light")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("自动记账").assertIsDisplayed()
        capture("settings-light")
        compose.onNodeWithText("自动记账").performClick()
        compose.onNodeWithText("无障碍").assertIsDisplayed()
        compose.onNodeWithText("悬浮窗").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("结余").assertIsDisplayed()
    }
    @Test fun populatedLedgerCapacityPreview() {
        compose.waitUntil(10_000) { !graph.loading.value }
        val context = compose.activity.applicationContext
        runBlocking(Dispatchers.IO) {
            LedgerStore(context).use { store ->
                repeat(16) { i ->
                    store.save(ConfirmedEntry(TransactionCandidate("com.tencent.mm",
                        if (i % 4 == 0) TransactionType.INCOME else TransactionType.EXPENSE,
                        Money(1234L + i), "样例商户 %02d".format(i + 1), "synthetic-density-%03d".format(i),
                        "2026-10-31 12:00:00", "test-v1", 1793419200000L - i * 60_000),
                        note = if (i % 3 == 0) "午餐" else ""))
                }
            }
        }
        compose.runOnIdle { graph.refresh() }
        compose.waitUntil(10_000) { graph.snapshot.value.count == 16 }
        compose.waitForIdle()
        capture("ledger-density")
        val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        var visible = 0
        fun fullTextHeight(value: String): Int {
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value, substring = true, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single().size.height
        }
        repeat(16) { i ->
            val name = "样例商户 %02d".format(i + 1)
            val merchant = compose.onAllNodesWithText(name, substring = true).fetchSemanticsNodes().firstOrNull()
            val sign = if (i % 4 == 0) "+" else "−"
            val price = "$sign ¥ ${Money(1234L + i).decimal().toPlainString()}"
            val amount = compose.onAllNodesWithText(price)
                .fetchSemanticsNodes().firstOrNull()
            if (merchant != null && amount != null &&
                listOf(merchant, amount).all { it.boundsInRoot.top >= viewport.top && it.boundsInRoot.bottom <= viewport.bottom } &&
                merchant.boundsInRoot.height + 1 >= fullTextHeight(name) && amount.boundsInRoot.height + 1 >= fullTextHeight(price)) visible++
        }
        File("build/ui-previews/density-after.json").writeText("{\"visible_records\":$visible,\"sample_records\":16}")
        assertTrue("Visible ledger records: $visible", visible >= 7)
        compose.onNodeWithText("样例商户 01", substring = true).performClick()
        compose.onNodeWithText("账单详情").assertIsDisplayed()
        compose.onNodeWithText("午餐").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("¥ -99.44").assertIsDisplayed()
        capture("statistics-populated")
    }
    @Test fun confirmationValidatesAmountAndPersistsEditedEntry() {
        compose.waitUntil(10_000) { !graph.loading.value }
        compose.runOnIdle {
            graph.enable(true)
            graph.present(TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1800),
                "合成商户", "synthetic-ui-001", "2026-10-31 12:00:00", "test-v1", 1793419200000))
        }
        compose.waitUntil(10_000) { graph.pending.value?.preparation != null }
        compose.onNodeWithText("待确认", substring = true).performClick()
        compose.onNodeWithText("金额（元）").performTextReplacement("18.001")
        compose.onNodeWithText("保存", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, graph.snapshot.value.count) }
        compose.onNodeWithText("金额（元）").performTextReplacement("18.01")
        capture("candidate-light")
        compose.onNodeWithText("保存", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { graph.pending.value == null && graph.snapshot.value.count == 1 }
        compose.onNodeWithText("− ¥ 18.01").assertIsDisplayed()
        capture("ledger-saved-light")
    }
    @Suppress("DEPRECATION")
    @Test fun syntheticAccessibilityPageReachesOverlayAndCancelAllowsImmediateReentry() {
        compose.waitUntil(10_000) { !graph.loading.value }
        val controller = Robolectric.buildService(LedgerAccessibilityService::class.java).create()
        val service = controller.get()
        fun show(texts: List<String>) {
            val root = AccessibilityNodeInfo.obtain().apply { packageName = "com.tencent.mm" }
            texts.forEach { text -> shadowOf(root).addChild(AccessibilityNodeInfo.obtain().apply { this.text = text }) }
            shadowOf(service).setRootInActiveWindow(root)
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = "com.tencent.mm"
            }
            service.onAccessibilityEvent(event)
            event.recycle()
        }
        val texts = listOf("账单详情", "合成商户", "-18.00", "支付时间", "2026-10-31 12:00:00",
            "交易单号", "synthetic-service-001")
        try {
            ShadowSettings.setCanDrawOverlays(true)
            ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
            compose.runOnIdle { graph.enable(true); graph.activityVisible = false; show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            compose.waitUntil(10_000) { graph.pending.value != null }
            compose.runOnIdle { graph.pause(true) }
            compose.waitUntil(10_000) { graph.pending.value == null }
            compose.runOnIdle { show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)); compose.waitForIdle()
            org.junit.Assert.assertNull(graph.pending.value)
            compose.runOnIdle { graph.pause(false) }
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                graph.pending.value != null
            }
            compose.onNodeWithContentDescription("收起记账窗口").performClick()
            val compactToken = graph.pending.value!!.token
            compose.runOnIdle { show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); compose.waitForIdle()
            assertEquals(compactToken, graph.pending.value?.token)
            // No foreign-app event is delivered with the manifest whitelist. A compact window
            // checks foreground identity only, and must disappear when the user changes apps.
            compose.runOnIdle {
                shadowOf(service).setRootInActiveWindow(AccessibilityNodeInfo.obtain().apply { packageName = "synthetic.other.app" })
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            compose.waitUntil(10_000) { graph.pending.value == null }
            compose.runOnIdle { show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            compose.waitUntil(10_000) { graph.pending.value != null }
            val first = graph.pending.value!!.token
            compose.runOnIdle { graph.discard(first); show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
            compose.waitForIdle()
            assertEquals(null, graph.pending.value)
            val repeatGuard = ReflectionHelpers.getField<PageRepeatGuard>(service, "guard")
            val detailGeneration = repeatGuard.ticket()?.generation
            compose.runOnIdle { show(listOf("微信", "账单列表")) }
            // Advance delayed work until the background parse has actually confirmed departure.
            // Fixed clock jumps can finish before Dispatchers.Default schedules the parse.
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
                repeatGuard.ticket()?.generation != detailGeneration
            }
            compose.runOnIdle { show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            compose.waitUntil(10_000) { graph.pending.value != null }
            compose.runOnIdle { graph.confirm(graph.pending.value!!.token, ConfirmedEntry(graph.pending.value!!.candidate)) }
            compose.waitUntil(10_000) { graph.snapshot.value.count == 1 && graph.pending.value == null }
            compose.runOnIdle { show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)); compose.waitForIdle()
            assertEquals(null, graph.pending.value)
            val savedGeneration = repeatGuard.ticket()?.generation
            compose.runOnIdle { show(listOf("微信", "账单列表")) }
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
                repeatGuard.ticket()?.generation != savedGeneration
            }
            compose.runOnIdle { show(texts) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            compose.waitUntil(10_000) { graph.pending.value?.preparation?.repeated == true }
            compose.runOnIdle { graph.confirm(graph.pending.value!!.token, ConfirmedEntry(graph.pending.value!!.candidate)) }
            compose.waitUntil(10_000) { graph.snapshot.value.count == 2 && graph.pending.value == null }
        } finally { controller.destroy(); graph.activityVisible = true }
    }
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h740dp-night-mdpi")
    fun narrowDarkScreenWithLargeFontKeepsPermissionsReachable() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { !graph.loading.value }
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("自动记账").performClick()
            compose.onNodeWithText("无障碍").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("悬浮窗").performScrollTo().assertIsDisplayed()
            capture("settings-dark-320-font200")
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h740dp-night-mdpi")
    fun fullTransactionTextAndLargeAmountRemainReadable() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { !graph.loading.value }
            val context = compose.activity.applicationContext
            val merchant = "样例长名称商户用于核对完整交易对方".repeat(3)
            val note = "账单备注保留完整内容。".repeat(8)
            runBlocking(Dispatchers.IO) {
                LedgerStore(context).use { it.save(ConfirmedEntry(TransactionCandidate("com.tencent.mm",
                    TransactionType.EXPENSE, Money(Long.MAX_VALUE), merchant, "synthetic-long-001",
                    "2026-10-31 12:00:00", "test-v1", 1793419200000), note = note)) }
            }
            compose.runOnIdle { graph.refresh() }
            compose.waitUntil(10_000) { graph.snapshot.value.count == 1 }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(merchant, substring = true))
            compose.onNodeWithText(merchant, substring = true).performScrollTo().performClick()
            val exactAmount = "− ¥ ${Money(Long.MAX_VALUE).decimal().toPlainString()}"
            compose.onNodeWithText(exactAmount).performScrollTo().assertIsDisplayed()
            assertNoTextOverflow(exactAmount)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(merchant))
            compose.onNodeWithText(merchant).performScrollTo().assertIsDisplayed()
            assertNoTextOverflow(merchant)
            capture("details-long-dark-font200")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(note))
            compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
            assertNoTextOverflow(note)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText(merchant, substring = true).assertExists()
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }
    private fun assertNoTextOverflow(value: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Text must be laid out in full", results.isNotEmpty() && results.none { it.hasVisualOverflow })
    }
    private fun capture(name: String) {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            // Draw the actual Android window, avoiding PixelCopy's unsupported callback in Robolectric.
            val global = Class.forName("android.view.WindowManagerGlobal")
            val manager = ReflectionHelpers.callStaticMethod<Any>(global, "getInstance")
            val views = ReflectionHelpers.getField<List<android.view.View>>(manager, "mViews")
            val view = views.last()
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val folder = File("build/ui-previews").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
