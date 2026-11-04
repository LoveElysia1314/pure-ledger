package io.github.localledger.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Context
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.localledger.app.accessibility.LedgerAccessibilityService
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AutomaticRuntimeTest {
    @org.junit.Before fun useAllTimeForHistoricalFixtures() {
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
        compose.runOnIdle { graph.setQuery(io.github.localledger.core.LedgerQuery()) }
        compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = compose.activity.ledgerGraph
    private val app get() = compose.activity.application
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private var receiver: LedgerNotificationReceiver? = null
    private fun ready() = compose.waitUntil(10_000) { !graph.loading.value && !graph.busy.value }
    @Test fun batterySettingsTargetsThisAppAndRefreshDoesNotGateRecognition() {
        val intents=batterySettingsIntents(app.packageName)
        assertEquals("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",intents.first().action)
        assertEquals("package:${app.packageName}",intents.first().data.toString())
        assertEquals(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,intents[1].action)
        assertEquals("package:${app.packageName}",intents.last().data.toString())
        val manager=app.getSystemService(android.os.PowerManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setIgnoringBatteryOptimizations(app.packageName,true)
        compose.runOnIdle{graph.refreshRuntime()}
        assertTrue(graph.runtimePermissions.value.batteryUnrestricted)
        val allowed=RuntimePermissions(true,true,true,false)
        assertEquals(AutomaticStatus.RUNNING,resolveAutomaticStatus(true,false,ServiceConnection.CONNECTED,allowed))
    }
    @Before fun bindDeclaredReceiverInTheLocalRuntime() {
        val info = app.packageManager.getReceiverInfo(ComponentName(app, LedgerNotificationReceiver::class.java), 0)
        assertFalse(info.exported)
        // This binary-manifest Robolectric setup exposes ReceiverInfo, but does not bind its
        // live instance. Model the OS binding after checking the production declaration.
        if (!shadowOf(app).hasReceiverForIntent(Intent(app, LedgerNotificationReceiver::class.java).setAction(LedgerNotifications.PAUSE))) {
            receiver = LedgerNotificationReceiver().also { app.registerReceiver(it,
                IntentFilter(LedgerNotifications.PAUSE).apply { addAction(LedgerNotifications.RESUME) }, Context.RECEIVER_NOT_EXPORTED) }
        }
    }
    @After fun close() { compose.runOnIdle { receiver?.let(app::unregisterReceiver); graph.enable(false); graph.close() } }
    private fun allow(pkg: String = app.packageName) {
        val info = AccessibilityServiceInfo()
        ReflectionHelpers.setField(info, "mResolveInfo", ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = pkg; name = LedgerAccessibilityService::class.java.name } })
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).setEnabledAccessibilityServiceList(listOf(info))
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        graph.refreshRuntime()
    }
    private fun notification() = manager.activeNotifications.firstOrNull { it.id == LedgerNotifications.ID }?.notification
    @Test fun clearingAnOlderNoticeKeepsTheNewerMessageAndItsSeverity() {
        ready()
        compose.runOnIdle {
            graph.consumeQuickEntry(true,"First notice")
            val first=graph.notice.value!!
            graph.consumeQuickEntry(true,"Second notice")
            graph.clearNotice(first.id)
            assertEquals("Second notice",graph.notice.value!!.message)
            assertEquals(NoticeKind.INFO,graph.notice.value!!.kind)
            graph.clearNotice(graph.notice.value!!.id)
            assertNull(graph.notice.value)
        }
    }
    @Test fun authorizationAloneDoesNotClaimRunningAndLifecycleStateIsReported() {
        ready(); compose.runOnIdle { allow(); graph.enable(true) }
        compose.waitUntil(10_000) { graph.automaticStatus.value == AutomaticStatus.DISCONNECTED }
        val controller = Robolectric.buildService(LedgerAccessibilityService::class.java).create()
        try {
            compose.runOnIdle { ReflectionHelpers.callInstanceMethod<Void>(controller.get(), "onServiceConnected") }
            compose.waitUntil(10_000) { graph.automaticStatus.value == AutomaticStatus.RUNNING }
            compose.runOnIdle { controller.get().onInterrupt() }
            compose.waitUntil(10_000) { graph.automaticStatus.value == AutomaticStatus.INTERRUPTED }
            compose.runOnIdle { controller.get().onUnbind(Intent()) }
            compose.waitUntil(10_000) { graph.automaticStatus.value == AutomaticStatus.DISCONNECTED }
        } finally { compose.runOnIdle { controller.destroy() } }
        compose.runOnIdle { allow("synthetic.other.ledger"); graph.serviceConnection(ServiceConnection.CONNECTED) }
        compose.waitUntil(10_000) { graph.automaticStatus.value == AutomaticStatus.ACCESSIBILITY }
    }
    @Test fun optionalQuietNotificationPausesResumesAndOffersAnExplicitQuickEntryIntent() {
        ready(); compose.runOnIdle { allow(); graph.enable(true); graph.serviceConnection(ServiceConnection.CONNECTED) }
        assertNull(notification())
        compose.runOnIdle { graph.setNotificationEnabled(true) }
        compose.waitUntil(10_000) { notification() != null }
        assertEquals(R.drawable.ic_brand_notification, notification()!!.smallIcon.resId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(LedgerNotifications.CHANNEL).importance)
        assertTrue(notification()!!.extras.getString(android.app.Notification.EXTRA_TITLE)!!.contains("运行中"))
        assertEquals(LedgerNotifications.PAUSE, shadowOf(notification()!!.actions[0].actionIntent).savedIntent.action)
        compose.runOnIdle { notification()!!.actions[0].actionIntent.send(); shadowOf(android.os.Looper.getMainLooper()).idle() }
        compose.waitForIdle()
        assertTrue(graph.paused.value)
        compose.waitUntil(10_000) { graph.automaticStatus.value == AutomaticStatus.PAUSED && graph.paused.value && notification()!!.extras.getString(android.app.Notification.EXTRA_TITLE)!!.contains("已暂停") }
        assertFalse(graph.recognizing)
        compose.runOnIdle { notification()!!.actions[0].actionIntent.send(); shadowOf(android.os.Looper.getMainLooper()).idle() }
        compose.waitUntil(10_000) { graph.automaticStatus.value == AutomaticStatus.RUNNING && !graph.paused.value }
        compose.runOnIdle { notification()!!.actions[1].actionIntent.send() }
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(LedgerNotifications.NEW_ENTRY, intent.action); assertEquals(MainActivity::class.java.name, intent.component!!.className)
        compose.runOnIdle { graph.setNotificationEnabled(false) }
        compose.waitUntil(10_000) { notification() == null }
        assertTrue(graph.recognizing); assertEquals(0, graph.snapshot.value.count)
    }
    @Test fun deniedNotificationsDoNotDisableRecognitionAndQuickEntryKeepsAnExistingDraft() {
        ready(); compose.runOnIdle { allow(); graph.enable(true); graph.serviceConnection(ServiceConnection.CONNECTED); graph.setNotificationEnabled(true) }
        compose.waitUntil(10_000) { notification() != null }
        compose.runOnIdle { shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS); graph.refreshRuntime() }
        compose.waitUntil(10_000) { notification() == null && !graph.runtimePermissions.value.notifications }
        assertTrue(graph.recognizing)
        compose.runOnIdle { graph.requestQuickEntry() }
        compose.onNodeWithText("金额（元）").performTextReplacement("12.34")
        compose.onNodeWithContentDescription("交易对方").performScrollTo().performClick(); compose.onNodeWithText("交易对方").performTextInput("保留草稿"); compose.onNodeWithText("确定").performClick()
        compose.runOnIdle { graph.requestQuickEntry() }
        compose.onNodeWithText("金额（元）").assertTextContains("12.34")
        compose.onNodeWithText("保留草稿").assertIsDisplayed()
        assertEquals(0, graph.snapshot.value.count)
    }
    @Test fun reopeningRestoresAClearedNotificationWithUnchangedStateAndHonorsOptOut() {
        ready();compose.runOnIdle { allow();graph.enable(true);graph.serviceConnection(ServiceConnection.CONNECTED);graph.setNotificationEnabled(true) }
        compose.waitUntil(10_000){notification()!=null && graph.automaticStatus.value==AutomaticStatus.RUNNING}
        val status=graph.automaticStatus.value;val permissions=graph.runtimePermissions.value
        compose.runOnIdle { manager.cancel(LedgerNotifications.ID) };assertNull(notification())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000){notification()!=null}
        assertEquals(status,graph.automaticStatus.value);assertEquals(permissions,graph.runtimePermissions.value)
        assertEquals(1,manager.activeNotifications.count{it.id==LedgerNotifications.ID})
        compose.runOnIdle { graph.setNotificationEnabled(false) }
        compose.waitUntil(10_000){notification()==null}
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle();assertNull(notification());assertTrue(graph.recognizing)
        compose.runOnIdle { graph.setNotificationEnabled(true);shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS) }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle();assertNull(notification());assertFalse(graph.runtimePermissions.value.notifications)
    }
    @Test fun successFeedbackOnlyOccursAfterACommittedCandidateAndCanBeDisabled() {
        ready(); val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(123), "合成商户", "synthetic-notify-001", null, "v1", 1000)
        compose.runOnIdle { graph.enable(true); graph.present(source) }
        compose.waitUntil(10_000) { graph.pending.value?.preparation != null }
        compose.runOnIdle { graph.confirm(graph.pending.value!!.token, ConfirmedEntry(source)) }
        compose.waitUntil(10_000) { graph.snapshot.value.count == 1 && graph.pending.value == null }
        assertEquals("已记账", ShadowToast.getTextOfLatestToast())
        ShadowToast.reset()
        compose.runOnIdle { graph.setSuccessFeedback(false); graph.present(source.copy(externalId = "synthetic-notify-002")) }
        compose.waitUntil(10_000) { graph.pending.value?.preparation != null }
        compose.runOnIdle { graph.confirm(graph.pending.value!!.token, ConfirmedEntry(graph.pending.value!!.candidate)) }
        compose.waitUntil(10_000) { graph.snapshot.value.count == 2 && graph.pending.value == null }
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
