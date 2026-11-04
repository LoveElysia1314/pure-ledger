package io.github.localledger.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.localledger.app.ledgerGraph
import io.github.localledger.app.ServiceConnection
import kotlinx.coroutines.flow.combine
import io.github.localledger.app.data.SaveResult
import io.github.localledger.app.overlay.CandidateOverlay
import io.github.localledger.core.PageRepeatGuard
import io.github.localledger.core.PageSnapshot
import io.github.localledger.core.RecognitionResult
import io.github.localledger.core.PaymentDetailParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Captures detached, bounded snapshots on main; parsing and storage lookups run off main. */
class LedgerAccessibilityService : AccessibilityService() {
    private val parser = PaymentDetailParser()
    private val guard = PageRepeatGuard()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: CandidateOverlay
    private var recognition: Job? = null
    private var pendingSince = 0L
    private var windowId = -1
    private var connected = false
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) resetPage()
        }
    }
    private val capture = Runnable { pendingSince = 0; capturePage() }
    private val collapsedForegroundCheck = object : Runnable {
        override fun run() {
            if (!connected || !::overlay.isInitialized || !overlay.visible || overlay.expanded) return
            // Whitelisted event subscriptions do not report another app coming to the front.
            // While collapsed only, inspect foreground identity; never collect its page text.
            val active = rootInActiveWindow
            val foreground = try { active?.packageName?.toString() } finally { recycle(active) }
            if (foreground != null && foreground !in PAYMENT_APPS && foreground != packageName) { resetPage(); return }
            handler.postDelayed(this, 750)
        }
    }
    private var stableExit: Runnable? = null

    override fun onServiceConnected() {
        if (connected) return
        connected = true
        ledgerGraph.serviceConnection(ServiceConnection.CONNECTED)
        overlay = CandidateOverlay(this, ledgerGraph, ::resetPage) { expanded ->
            handler.removeCallbacks(collapsedForegroundCheck)
            if (!expanded) handler.postDelayed(collapsedForegroundCheck, 750)
        }
        ledgerGraph.dismissOverlay = overlay::close
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
        scope.launch { combine(ledgerGraph.enabled, ledgerGraph.paused) { enabled, paused -> enabled && !paused }.collect { active ->
            if (!active) resetPage()
            else if (connected && !ledgerGraph.activityVisible) {
                val root = rootInActiveWindow
                try {
                    if (root != null && root.packageName.toString() in PAYMENT_APPS) {
                        windowId = root.windowId; guard.observe(PageRepeatGuard.Window(root.packageName.toString(), windowId)); handler.post(capture)
                    }
                } finally { recycle(root) }
            }
        } }
        scope.launch {
            var saving = false
            ledgerGraph.pending.collect { pending ->
                if (saving && pending == null && ledgerGraph.recognizing && connected) handler.post(capture)
                saving = pending?.saving == true
            }
        }
        scope.launch {
            var busy = false
            ledgerGraph.busy.collect {
                if (busy && !it && connected && ledgerGraph.recognizing) handler.post(capture)
                busy = it
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null || !ledgerGraph.recognizing) return
        ledgerGraph.serviceConnection(ServiceConnection.CONNECTED)
        val app = event.packageName?.toString() ?: return
        if (app == packageName) {
            if (ledgerGraph.activityVisible) { overlay.close(); guard.leave(); cancelWork() }
            return
        }
        if (app !in PAYMENT_APPS) return
        windowId = event.windowId
        if (guard.observe(PageRepeatGuard.Window(app, windowId))) {
            overlay.close(); ledgerGraph.discard()
        }
        recognition?.cancel()
        stableExit?.let(handler::removeCallbacks); stableExit = null
        // Trailing capture catches late-loaded fields; bounded delay avoids starvation in event bursts.
        val now = SystemClock.elapsedRealtime()
        if (pendingSince == 0L) pendingSince = now
        handler.removeCallbacks(capture)
        handler.postDelayed(capture, minOf(180L, (600L - (now - pendingSince)).coerceAtLeast(0L)))
    }
    private fun rootForPage(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName.toString() in PAYMENT_APPS && active.windowId == windowId) return active
        recycle(active)
        if (!overlay.visible) return null
        // A focusable confirmation overlay may be the active root. Only inspect the allowed app below it.
        var found: AccessibilityNodeInfo? = null
        windows.forEach { window ->
            if (found == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.id == windowId) {
                val root = window.root
                if (root != null && root.packageName.toString() in PAYMENT_APPS) found = root else recycle(root)
            }
            @Suppress("DEPRECATION")
            window.recycle()
        }
        return found
    }
    private fun snapshot(): PageSnapshot? {
        val root = rootForPage() ?: return null
        val texts = mutableListOf<String>()
        var visited = 0
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || visited++ >= 100) return
            if (node.childCount == 0) {
                (node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription)?.toString()?.trim()
                    ?.takeIf(String::isNotEmpty)?.let(texts::add)
            } else for (i in 0 until node.childCount.coerceAtMost(100)) {
                if (visited >= 100) break
                val child = node.getChild(i) ?: continue
                try { collect(child, depth + 1) } finally { recycle(child) }
            }
        }
        return try { collect(root, 0); PageSnapshot(root.packageName.toString(), texts, System.currentTimeMillis()) }
        finally { recycle(root) }
    }
    private fun capturePage() {
        if (!connected || !ledgerGraph.recognizing) return
        if (!Settings.canDrawOverlays(this)) { ledgerGraph.refreshRuntime(); resetPage(); return }
        val ticket = guard.ticket() ?: return
        val pageSnapshot = snapshot() ?: return
        recognition = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { parser.parse(pageSnapshot) }
                if (!guard.unchanged(ticket)) return@launch
                when (result) {
                    is RecognitionResult.NoMatch -> {
                        stableExit = Runnable {
                            if (guard.unchanged(ticket)) {
                                // Recheck: a partial render is not evidence that the user left the page.
                                val current = snapshot()
                                if (current != null) recognition = scope.launch {
                                    val matched = withContext(Dispatchers.Default) { parser.parse(current) }
                                    if (guard.unchanged(ticket)) {
                                        if (matched is RecognitionResult.NoMatch) {
                                            guard.leaveIfUnchanged(ticket); overlay.close(); ledgerGraph.discard()
                                        } else capturePage()
                                    }
                                }
                            }
                        }.also { handler.postDelayed(it, 250) }
                    }
                    is RecognitionResult.Recognized -> {
                        val candidate = result.candidate
                        val preparation = ledgerGraph.prepare(candidate)
                        if (!guard.unchanged(ticket) || !connected || !ledgerGraph.recognizing) return@launch
                        // Recheck foreground after async work, before presenting a candidate.
                        val current = rootForPage() ?: return@launch
                        recycle(current)
                        val record = PageRepeatGuard.Record.of(candidate.appPackage, candidate.externalId.orEmpty(),
                            candidate.type.ordinal, candidate.merchant, candidate.amount.minor, candidate.occurredAtMs ?: 0)
                        if (!guard.claim(ticket, record, preparation.repeated, true)) return@launch
                        if (ledgerGraph.activityVisible || !Settings.canDrawOverlays(this@LedgerAccessibilityService)) {
                            guard.failedDisplay(); return@launch
                        }
                        if (ledgerGraph.present(candidate, preparation = preparation) == null) { guard.failedDisplay(); return@launch }
                        if (!overlay.show()) { ledgerGraph.discard(); guard.failedDisplay() }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: RuntimeException) { guard.failedDisplay() }
        }
    }
    private fun cancelWork() {
        handler.removeCallbacks(capture); pendingSince = 0
        handler.removeCallbacks(collapsedForegroundCheck)
        stableExit?.let(handler::removeCallbacks); stableExit = null
        recognition?.cancel(); recognition = null
    }
    private fun resetPage() {
        cancelWork(); guard.leave()
        if (::overlay.isInitialized) overlay.close()
        ledgerGraph.discard()
    }
    override fun onInterrupt() { ledgerGraph.serviceConnection(ServiceConnection.INTERRUPTED); resetPage() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { connected = false; ledgerGraph.serviceConnection(ServiceConnection.DISCONNECTED); resetPage(); return super.onUnbind(intent) }
    override fun onDestroy() {
        connected = false; resetPage(); guard.stop(); scope.cancel()
        ledgerGraph.serviceConnection(ServiceConnection.DISCONNECTED)
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false }
        ledgerGraph.dismissOverlay = null
        super.onDestroy()
    }
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) { node?.recycle() }
    companion object { private val PAYMENT_APPS = setOf("com.tencent.mm", "com.eg.android.AlipayGphone") }
}
