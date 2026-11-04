package io.github.localledger.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModelStore
import io.github.localledger.app.accessibility.LedgerAccessibilityService
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.ui.LedgerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionStateTest {
    private lateinit var app: Application
    private lateinit var graph: LedgerGraph
    private lateinit var model: LedgerViewModel
    private val models = ViewModelStore()
    @Before fun prepare() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        graph = LedgerGraph(app, LedgerStore(app, "synthetic-permissions.db"), dispatcher)
        graph.enable(false)
        model = LedgerViewModel(app, graph)
        models.put("ledger", model)
        ShadowSettings.setCanDrawOverlays(true)
    }
    @After fun close() { models.clear(); graph.close(); Dispatchers.resetMain() }
    private fun setService(pkg: String, name: String) {
        val resolve = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; this.name = name } }
        val info = AccessibilityServiceInfo()
        ReflectionHelpers.setField(info, "mResolveInfo", resolve)
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).setEnabledAccessibilityServiceList(listOf(info))
    }
    @Test fun permissionForOtherPackageOrOtherServiceCannotEnableAutomaticBookkeeping() {
        setService("example.other.ledger", LedgerAccessibilityService::class.java.name)
        model.resume(); model.enable(true)
        assertFalse(model.permissions.value.accessibility)
        assertFalse(graph.enabled.value)
        setService(app.packageName, "example.other.Service")
        model.resume(); model.enable(true)
        assertFalse(model.permissions.value.accessibility)
        assertFalse(graph.enabled.value)
    }
    @Test fun exactComponentAndOverlayPermissionEnableAutomaticBookkeeping() {
        setService(app.packageName, LedgerAccessibilityService::class.java.name)
        model.resume(); model.enable(true)
        assertTrue(model.permissions.value.ready)
        assertTrue(graph.enabled.value)
    }
}
