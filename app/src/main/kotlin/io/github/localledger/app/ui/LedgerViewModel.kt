package io.github.localledger.app.ui

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.localledger.app.LedgerGraph
import io.github.localledger.app.accessibility.LedgerAccessibilityService
import io.github.localledger.app.data.LedgerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow

data class PermissionState(val accessibility: Boolean = false, val overlay: Boolean = false) {
    val ready get() = accessibility && overlay
}
data class LedgerUiState(val ledger: LedgerSnapshot = LedgerSnapshot(), val loading: Boolean = true,
                         val error: String? = null)

class LedgerViewModel(private val application: Application, val graph: LedgerGraph) : ViewModel() {
    internal val updates = io.github.localledger.app.update.UpdateController(viewModelScope,
        application.getSharedPreferences("preferences", android.content.Context.MODE_PRIVATE),
        io.github.localledger.app.update.ReleaseSource.configured(), requireNotNull(io.github.localledger.core.AppVersion.parse(io.github.localledger.app.BuildConfig.VERSION_NAME)))
    private val _permissions = MutableStateFlow(PermissionState())
    val permissions = _permissions.asStateFlow()
    val state = combine(graph.snapshot, graph.loading, graph.error) { ledger, loading, error ->
        LedgerUiState(ledger, loading, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LedgerUiState())
    fun resume() {
        graph.onAppForeground()
        val manager = application.getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(application, LedgerAccessibilityService::class.java)
        val accessibility = manager.getEnabledAccessibilityServiceList(-1).any {
            val service = it.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == expected
        }
        _permissions.value = PermissionState(accessibility, Settings.canDrawOverlays(application))
        graph.refresh()
    }
    fun enable(value: Boolean) { if (!value || permissions.value.ready) graph.enable(value) }
}
