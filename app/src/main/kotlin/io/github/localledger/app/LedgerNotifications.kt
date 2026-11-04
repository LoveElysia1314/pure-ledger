package io.github.localledger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Optional status notification. No payment details or foreground-service claim. */
internal class LedgerNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun update(show: Boolean, status: AutomaticStatus, paused: Boolean, allowed: Boolean) {
        if (!show || !allowed || status == AutomaticStatus.OFF) { manager.cancel(ID); return }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, appString(R.string.ui_automatic_entries), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val create = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java).setAction(NEW_ENTRY).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val toggle = PendingIntent.getBroadcast(context, 3, Intent(context, LedgerNotificationReceiver::class.java).setAction(if (paused) RESUME else PAUSE), flags)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_brand_notification)
            .setCategory(Notification.CATEGORY_SERVICE).setColor(context.getColor(R.color.brand_ink))
            .setContentTitle(appString(R.string.ui_automatic_entries_cc3cd, status.title)).setContentIntent(open).setOngoing(true).setShowWhen(false)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, if (paused) appString(R.string.ui_resume) else appString(R.string.ui_pause), toggle).build())
            .addAction(Notification.Action.Builder(null, appString(R.string.ui_add_entry), create).build()).build()
        try { manager.notify(ID, notification) } catch (_: SecurityException) { manager.cancel(ID) }
    }
    fun cancel() = manager.cancel(ID)
    companion object {
        const val CHANNEL = "automatic_status"
        const val ID = 4101
        const val PAUSE = "io.github.localledger.app.PAUSE"
        const val RESUME = "io.github.localledger.app.RESUME"
        const val NEW_ENTRY = "io.github.localledger.app.NEW_ENTRY"
    }
}
class LedgerNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.ledgerGraph
        when (intent.action) {
            LedgerNotifications.PAUSE -> graph.pause(true)
            LedgerNotifications.RESUME -> graph.pause(false)
        }
        graph.refreshRuntime()
    }
}
