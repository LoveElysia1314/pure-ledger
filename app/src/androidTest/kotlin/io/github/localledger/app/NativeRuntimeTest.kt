package io.github.localledger.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.accessibility.LedgerAccessibilityService
import io.github.localledger.app.data.LedgerStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual system binding/notification paths on the named AVD, with a disposable ledger. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Default UiAutomation disables other accessibility services, which would invalidate this test.
    private val ui by lazy { requireNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)) }
    private fun shell(command: String) = ui.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
    }
    private fun until(label: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Timed out: $label" }
            android.os.SystemClock.sleep(50)
        }
    }
    private fun find(root: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 2000) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && match(node)) return node
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return null
    }
    private fun click(match: (AccessibilityNodeInfo) -> Boolean) {
        var clicked = false
        until("visible actionable control") {
            ui.clearCache()
            var node = find(ui.rootInActiveWindow, match)
            var parents = 0
            while (node != null && !node.isClickable && parents++ < 5) node = node.parent
            clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            clicked
        }
    }
    private fun text(value: String): (AccessibilityNodeInfo) -> Boolean = { node ->
        node.text?.toString()?.lineSequence()?.any { it.trim() == value } == true
    }
    private fun expandNotification() {
        shell("cmd statusbar expand-notifications")
        until("own notification") { find(ui.rootInActiveWindow) { it.text?.toString()?.contains("自动记账 ·") == true } != null }
        if (find(ui.rootInActiveWindow, text("暂停")) != null || find(ui.rootInActiveWindow, text("继续")) != null) return
        var node = find(ui.rootInActiveWindow) { it.text?.toString()?.contains("自动记账 ·") == true }
        repeat(7) {
            val button = find(node) { it.viewIdResourceName?.endsWith("/expand_button") == true }
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node?.parent
        }
    }
    private fun capture(context: android.content.Context, name: String) {
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(200)
        val image = ui.takeScreenshot() ?: error("No compositor frame")
        val folder = File(context.filesDir, "runtime-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
    private fun restoreSetting(key: String, value: String) {
        if (value == "null" || value.isEmpty()) shell("settings delete secure $key")
        else { check(value.matches(Regex("[A-Za-z0-9_.$/:]+"))); shell("settings put secure $key $value") }
    }
    @Test fun connectedServiceAndSystemNotificationSupportPauseResumeAndQuickEntry() {
        check(shell("getprop ro.boot.qemu.avd_name") == "LocalLedger_API35") { "Requires the isolated AVD; no phone may be used." }
        val app = instrumentation.targetContext.applicationContext as LedgerApplication
        AppStrings.initialize(chineseCopyContext(app))
        File(app.filesDir, "runtime-review").listFiles()?.filter { it.isFile && it.extension == "png" }?.forEach { check(it.delete()) }
        val expected = ComponentName(app, LedgerAccessibilityService::class.java)
        val component = expected.flattenToString()
        val oldServices = shell("settings get secure enabled_accessibility_services")
        val oldAccessibility = shell("settings get secure accessibility_enabled")
        check(oldServices.split(':').none { ComponentName.unflattenFromString(it) == expected }) { "Own service must start disabled so the test can isolate its graph." }
        check(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "The AVD harness must pre-grant notifications and restore it after instrumentation exits." }
        val database = "runtime-${System.nanoTime()}.db"
        lateinit var graph: LedgerGraph
        instrumentation.runOnMainSync {
            graph = LedgerGraph(app, LedgerStore(app, database))
            app.javaClass.getDeclaredField("graph\$delegate").apply { isAccessible = true }.set(app, lazy { graph })
        }
        val oldEnabled = graph.enabled.value; val oldPaused = graph.paused.value
        val oldNotify = graph.notificationEnabled.value; val oldAppearance = graph.appearance.value
        var activity: MainActivity? = null
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            until("ledger ready") { !graph.loading.value && !graph.busy.value }
            instrumentation.runOnMainSync { graph.setAppearance(Appearance.DARK); graph.pause(false) }
            val services = (oldServices.takeUnless { it == "null" || it.isEmpty() }?.split(':').orEmpty() + component).distinct().joinToString(":")
            check(services.matches(Regex("[A-Za-z0-9_.$/:]+")))
            shell("settings put secure enabled_accessibility_services $services")
            assertEquals(services, shell("settings get secure enabled_accessibility_services"))
            shell("settings put secure accessibility_enabled 1")
            until("exact service component enabled") { app.getSystemService(AccessibilityManager::class.java).getEnabledAccessibilityServiceList(-1).any {
                ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected
            } }
            instrumentation.runOnMainSync { graph.refreshRuntime(); graph.enable(true) }
            until("real service connected") { graph.automaticStatus.value == AutomaticStatus.RUNNING }
            click(text("设置")); click(text("自动记账"))
            until("running state visible") { find(ui.rootInActiveWindow, text("运行中")) != null }
            capture(app, "runtime-connected")
            if (!graph.notificationEnabled.value) click { it.contentDescription?.toString() == "常驻通知" }
            until("real notification posted") { graph.notificationEnabled.value && manager.activeNotifications.any { it.id == LedgerNotifications.ID } }
            assertEquals(R.drawable.ic_brand_notification, manager.activeNotifications.single { it.id == LedgerNotifications.ID }.notification.smallIcon.resId)
            capture(app, "runtime-notification-enabled")
            expandNotification()
            val own=requireNotNull(find(ui.rootInActiveWindow) { it.text?.toString()?.contains("自动记账 ·") == true })
            val bounds=android.graphics.Rect().also(own::getBoundsInScreen)
            val display=android.util.DisplayMetrics().also{app.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it)}
            shell("input swipe ${display.widthPixels/2} ${bounds.centerY()} ${display.widthPixels-2} ${bounds.centerY()} 350")
            until("user swipe removed status notification") { manager.activeNotifications.none{it.id==LedgerNotifications.ID} }
            capture(app,"notification-dismissed")
            shell("cmd statusbar collapse");shell("input keyevent 3")
            shell("am start -n io.github.localledger.app/.MainActivity")
            until("reopening restored the same notification") { manager.activeNotifications.count{it.id==LedgerNotifications.ID}==1 }
            assertEquals(AutomaticStatus.RUNNING,graph.automaticStatus.value)
            expandNotification();capture(app,"notification-restored")
            expandNotification(); capture(app, "notification-running")
            click(text("暂停")); until("pause action delivered") { graph.paused.value && graph.automaticStatus.value == AutomaticStatus.PAUSED }
            until("pause notification refreshed") { manager.activeNotifications.any { it.id == LedgerNotifications.ID && it.notification.extras.getCharSequence("android.title").toString().contains("已暂停") } }
            expandNotification(); capture(app, "notification-paused")
            click(text("继续")); until("resume action delivered") { !graph.paused.value && graph.automaticStatus.value == AutomaticStatus.RUNNING }
            expandNotification(); click(text("记一笔"))
            try {
                until("notification opens the entry workspace with note focus") {
                    ui.windows.any { window ->
                        val label=find(window.root){it.contentDescription?.toString()=="备注"}
                        val input=find(window.root){it.isEditable && it.isFocused}
                        if(label==null || input==null)false else {
                            val labelBounds=android.graphics.Rect().also(label::getBoundsInScreen)
                            val inputBounds=android.graphics.Rect().also(input::getBoundsInScreen)
                            android.graphics.Rect.intersects(labelBounds,inputBounds)
                        }
                    }
                }
            } catch(error: Exception) {
                capture(app,"notification-quick-entry-failed")
                val state=StringBuilder("quickEntry=${graph.quickEntry.value} visible=${graph.activityVisible} busy=${graph.busy.value}\n")
                ui.windows.forEach { window ->
                    state.append("WINDOW type=${window.type} active=${window.isActive} focused=${window.isFocused}\n")
                    val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
                    window.root?.let(queue::add)
                    var count=0
                    while(queue.isNotEmpty() && count++<500) {
                        val node=queue.removeFirst()
                        if(!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() || node.isFocused)
                            state.append("${node.className} text=${node.text} desc=${node.contentDescription} focus=${node.isFocused} editable=${node.isEditable} bounds=${android.graphics.Rect().also(node::getBoundsInScreen)}\n")
                        repeat(node.childCount){node.getChild(it)?.let(queue::add)}
                    }
                }
                File(app.filesDir,"runtime-review/quick-entry-state.txt").writeText(state.toString())
                throw error
            }
            assertEquals(0, graph.snapshot.value.count); capture(app, "notification-quick-entry")
            shell("input keyevent 4")
            until("note keyboard closed") { find(ui.rootInActiveWindow) { it.contentDescription?.toString()=="金额键盘" } != null }
            shell("input keyevent 4")
            until("entry closed") { find(ui.rootInActiveWindow) { it.contentDescription?.toString() == "金额键盘" } == null && find(ui.rootInActiveWindow, text("设置")) != null }
            File(app.filesDir, "runtime-result.json").writeText(JSONObject().put("component", component).put("avd", "LocalLedger_API35")
                .put("real_service_connected", true).put("notification_posted", true).put("system_pause_resume", true)
                .put("notification_quick_entry", true).put("user_dismissed_notification",true).put("reopen_restored_notification",true)
                .put("notification_brand_icon",true).put("quick_entry_note_focused",true)
                .put("ledger_rows", graph.snapshot.value.count).toString(2))
        } finally {
            shell("cmd statusbar collapse")
            instrumentation.runOnMainSync { graph.setNotificationEnabled(oldNotify); graph.enable(oldEnabled); graph.pause(oldPaused); graph.setAppearance(oldAppearance) }
            restoreSetting("enabled_accessibility_services", oldServices); restoreSetting("accessibility_enabled", oldAccessibility)
            instrumentation.runOnMainSync { activity?.finish(); graph.close() }
            app.deleteDatabase(database)
        }
    }
}
