package io.github.localledger.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.ui.AboutDialog
import io.github.localledger.app.ui.LedgerTheme
import io.github.localledger.app.update.*
import io.github.localledger.core.AppVersion
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeUpdateUiTest {
    companion object {
        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private val app get() = instrumentation.targetContext.applicationContext as LedgerApplication
        private var previousAutomatic: Boolean? = null
        private var previousFont = "1.0"
        private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        @JvmStatic @BeforeClass fun prepare() {
            val name = shell("getprop ro.boot.qemu.avd_name")
            check(name == "LocalLedger_API35")
            val prefs = app.getSharedPreferences("preferences", 0)
            previousAutomatic = if (prefs.contains("automatic_updates")) prefs.getBoolean("automatic_updates", true) else null
            prefs.edit().putBoolean("automatic_updates", false).commit()
            previousFont = shell("settings get system font_scale")
            shell("settings put system font_scale 2.0")
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (app.resources.configuration.fontScale != 2f) {
                check(android.os.SystemClock.uptimeMillis() < deadline)
                android.os.SystemClock.sleep(50)
            }
        }
        @JvmStatic @AfterClass fun restore() {
            if (previousFont == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $previousFont")
            app.getSharedPreferences("preferences", 0).edit().apply {
                previousAutomatic?.let { putBoolean("automatic_updates", it) } ?: remove("automatic_updates")
            }.commit()
        }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(app.filesDir, "update-review").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun aboutShowsBothLatestSourcesLoadingFailureAndLargeTextWithoutBlockingActions() {
        val version = requireNotNull(AppVersion.parse("0.7.0"))
        val releases = listOf(ReleaseHost.GITEE, ReleaseHost.GITHUB).map {
            val source = ReleaseSource(it, "synthetic/pure-ledger")
            AppRelease(version, source, source.page("v0.7.0"))
        }
        var state by mutableStateOf(UpdateState(releases = releases, result = UpdateMessage.AVAILABLE))
        var dark by mutableStateOf(false)
        var checks = 0
        val opened = mutableListOf<AppRelease>()
        compose.runOnUiThread { compose.activity.setContent {
                LedgerTheme(if (dark) Appearance.DARK else Appearance.LIGHT) {
                    AboutDialog("0.6.2", {}, {}, state, { checks++ }, { state = state.copy(automatic = it) }, { opened += it })
                }
        } }
        val checkText = appString(R.string.ui_check_updates)
        val automaticText = appString(R.string.ui_automatic_updates)
        compose.onNodeWithText(appString(R.string.ui_update_available, "0.7.0")).assertIsDisplayed()
        releases.forEach { release ->
            compose.onNodeWithText(appString(R.string.ui_open_release, release.source.host.label)).performScrollTo().performClick()
        }
        assertEquals(releases, opened)
        capture("update-light")
        compose.onNodeWithText(automaticText).performScrollTo().performClick()
        assertFalse(state.automatic)
        compose.onNodeWithText(checkText).performScrollTo().performClick()
        assertEquals(1, checks)
        compose.runOnIdle { state = state.copy(checking = true) }
        compose.onNodeWithText(appString(R.string.ui_checking_updates)).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(checking = false, releases = emptyList(), result = UpdateMessage.FAILED) }
        compose.onNodeWithText(appString(R.string.ui_update_failed)).performScrollTo().assertIsDisplayed()
        capture("update-failed")
        compose.runOnIdle { state = state.copy(releases = releases, result = UpdateMessage.AVAILABLE); dark = true }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(appString(R.string.ui_open_release, "GitHub")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(appString(R.string.ui_open_release, "GitHub")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(appString(R.string.ui_close)).assertIsDisplayed()
        capture("update-dark-font200")
        compose.onNodeWithText(checkText).performScrollTo().assertIsDisplayed()
    }
}
