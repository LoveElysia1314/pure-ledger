package io.github.localledger.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.github.localledger.app.accessibility.LedgerAccessibilityService

enum class ServiceConnection { DISCONNECTED, CONNECTED, INTERRUPTED }
enum class AutomaticStatus(private val titleRes: Int) {
    OFF(R.string.ui_off), ACCESSIBILITY(R.string.ui_accessibility_disabled), OVERLAY(R.string.ui_overlay_permission_needed),
    DISCONNECTED(R.string.ui_service_disconnected), INTERRUPTED(R.string.ui_service_interrupted), PAUSED(R.string.ui_paused), RUNNING(R.string.ui_recognizing);
    val title: String get() = appString(titleRes)
}
data class RuntimePermissions(val accessibility: Boolean, val overlay: Boolean, val notifications: Boolean, val batteryUnrestricted:Boolean=false)

internal fun readRuntimePermissions(context: Context): RuntimePermissions {
    val expected = ComponentName(context, LedgerAccessibilityService::class.java)
    val accessibility = context.getSystemService(AccessibilityManager::class.java).getEnabledAccessibilityServiceList(-1).any {
        ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    val posted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val channel = manager.getNotificationChannel(LedgerNotifications.CHANNEL)
    return RuntimePermissions(accessibility, Settings.canDrawOverlays(context), posted && manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE,
        context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
}
internal fun resolveAutomaticStatus(enabled: Boolean, paused: Boolean, connection: ServiceConnection, permissions: RuntimePermissions): AutomaticStatus = when {
    !enabled -> AutomaticStatus.OFF
    !permissions.accessibility -> AutomaticStatus.ACCESSIBILITY
    !permissions.overlay -> AutomaticStatus.OVERLAY
    connection == ServiceConnection.DISCONNECTED -> AutomaticStatus.DISCONNECTED
    connection == ServiceConnection.INTERRUPTED -> AutomaticStatus.INTERRUPTED
    paused -> AutomaticStatus.PAUSED
    else -> AutomaticStatus.RUNNING
}
