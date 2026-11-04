package io.github.localledger.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

internal fun batterySettingsIntents(packageName:String)=listOf(
    Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL","package:$packageName".toUri()),
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:$packageName".toUri()),
)

/** Open the app battery page when supported, then the system's standard alternatives. */
internal fun openBatterySettings(context:Context):Boolean {
    batterySettingsIntents(context.packageName).forEach { intent ->
        try { context.startActivity(intent);return true }
        catch(_:ActivityNotFoundException) { }
        catch(_:SecurityException) { }
    }
    return false
}
