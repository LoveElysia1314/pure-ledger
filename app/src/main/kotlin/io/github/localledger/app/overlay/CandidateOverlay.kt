package io.github.localledger.app.overlay
import io.github.localledger.app.appString


import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import io.github.localledger.app.LedgerGraph
import io.github.localledger.app.R
import io.github.localledger.app.ui.CandidatePanel
import io.github.localledger.app.ui.LedgerTheme

/** Own lifecycle per attached overlay. No Activity context or owner retained by the service. */
class CandidateOverlay(context: Context, private val graph: LedgerGraph, private val onFocusLost: () -> Unit = {},
                       private val onExpansionChanged: (Boolean) -> Unit = {}) {
    private val baseContext = context
    private lateinit var themed: ContextThemeWrapper
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var focusGeneration = 0L
    val visible get() = view != null
    var expanded: Boolean = true
        private set

    fun show(): Boolean {
        if (visible) return true
        themed = ContextThemeWrapper(io.github.localledger.app.AppLanguages.wrap(baseContext), android.R.style.Theme_Material_Light_NoActionBar)
        val lifecycle = OverlayOwner { graph.discard() }
        val content = ComposeView(themed).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewTreeOnBackPressedDispatcherOwner(lifecycle)
            setContent {
                val pending by graph.pending.collectAsState()
                val ledger by graph.snapshot.collectAsState()
                val appearance by graph.appearance.collectAsState()
                val categoryDisplay by graph.categoryDisplay.collectAsState()
                val dark = appearance.isDark(isSystemInDarkTheme())
                SideEffect { themed.setTheme(if (dark) R.style.Theme_LocalLedger_Dark else R.style.Theme_LocalLedger_Light) }
                LedgerTheme(appearance,categoryDisplay) {
                    val candidate = pending
                    if (candidate != null) CandidatePanel(candidate, ledger.catalog, ledger.selection,
                        modifier = Modifier.fillMaxWidth().imePadding().onPreviewKeyEvent { key ->
                            if (key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                                if (key.nativeKeyEvent.action == KeyEvent.ACTION_UP) lifecycle.onBackPressedDispatcher.onBackPressed()
                                true
                            } else false
                        }, onSave = { graph.confirm(candidate.token, it) }, onCancel = { graph.discard(candidate.token) },
                        onRetry = { graph.retryPreparation(candidate.token) }, onExpandedChange = ::updateExpansion)
                    LaunchedEffect(candidate) { if (candidate == null) close() }
                }
            }
        }
        var hadFocus = false
        focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) hadFocus = true
            else if (hadFocus) {
              val observed = focusGeneration
              content.post {
                if (observed == focusGeneration && view === content && expanded && !content.hasWindowFocus()) onFocusLost()
              }
            }
        }.also { content.viewTreeObserver.addOnWindowFocusChangeListener(it) }
        val density = themed.resources.displayMetrics.density
        val width = minOf(themed.resources.displayMetrics.widthPixels - (16 * density).toInt(), (560 * density).toInt())
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (8 * density).toInt()
            dimAmount = .12f
            windowAnimations = R.style.LedgerOverlay_Animation
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = appString(R.string.ui_confirm_entry)
        }
        return try {
            expanded = true; owner = lifecycle; view = content
            manager.addView(content, params)
            if (android.os.Build.VERSION.SDK_INT >= 33) content.findOnBackInvokedDispatcher()?.let(lifecycle.onBackPressedDispatcher::setOnBackInvokedDispatcher)
            lifecycle.resume()
            true
        } catch (_: RuntimeException) { close(); false }
    }
    private fun updateExpansion(value: Boolean) {
        focusGeneration++
        expanded = value
        val current = view ?: return
        val params = current.layoutParams as? WindowManager.LayoutParams ?: return
        if (value) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            params.dimAmount = .12f
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            params.dimAmount = 0f
        }
        try { manager.updateViewLayout(current, params) } catch (_: IllegalArgumentException) { /* Window already removed. */ }
        onExpansionChanged(value)
    }
    fun close() {
        focusGeneration++
        val old = view ?: return
        view = null
        focusListener?.let { if (old.viewTreeObserver.isAlive) old.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
        focusListener = null
        old.disposeComposition()
        try { manager.removeViewImmediate(old) } catch (_: IllegalArgumentException) { /* Already detached. */ }
        owner?.destroy(); owner = null
    }
}

private class OverlayOwner(onBack: () -> Unit) : SavedStateRegistryOwner, LifecycleOwner, OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    override val onBackPressedDispatcher = OnBackPressedDispatcher(onBack)
    init {
        saved.performAttach(); saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
