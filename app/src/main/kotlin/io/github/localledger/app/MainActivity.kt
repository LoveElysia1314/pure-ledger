package io.github.localledger.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.localledger.app.ui.LedgerApp
import io.github.localledger.app.ui.LedgerTheme
import io.github.localledger.app.ui.LedgerViewModel

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLanguages.wrap(newBase)) }
    private val model by viewModels<LedgerViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LedgerViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return LedgerViewModel(application, ledgerGraph) as T
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val appearance = Appearance.read(getSharedPreferences("preferences", MODE_PRIVATE).getString("appearance", null))
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        setTheme(if (appearance.isDark(night)) R.style.Theme_LocalLedger_Dark else R.style.Theme_LocalLedger_Light)
        requestedOrientation=if(resources.configuration.smallestScreenWidthDp<600)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) consumeQuickIntent(intent)
        enableEdgeToEdge()
        setContent {
            var languagePicker by rememberSaveable { mutableStateOf(false) }
            val appearance by ledgerGraph.appearance.collectAsStateWithLifecycle()
            val categoryDisplay by ledgerGraph.categoryDisplay.collectAsStateWithLifecycle()
            val dark = appearance.isDark(isSystemInDarkTheme())
            SideEffect {
                setTheme(if (dark) R.style.Theme_LocalLedger_Dark else R.style.Theme_LocalLedger_Light)
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                window.setBackgroundDrawable(ColorDrawable(io.github.localledger.app.ui.ledgerColors(dark).background.toArgb()))
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(ledgerGraph::inspectFile) }
            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(ledgerGraph::exportTo) }
            val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ledgerGraph.refreshRuntime() }
            LedgerTheme(appearance,categoryDisplay) {
                LedgerApp(model, onAccessibility = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, onOverlay = {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }, onImport = { importLauncher.launch(arrayOf("*/*")) }, onExport = {
                    exportLauncher.launch(appString(R.string.ui_localledger_zip, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))))
                }, onNotifications = { value ->
                    ledgerGraph.setNotificationEnabled(value)
                    if (value && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }, onNotificationSettings = {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }, onBatterySettings = {
                    if(!openBatterySettings(this))ledgerGraph.systemSettingsUnavailable()
                }, onLanguageSettings = {
                    languagePicker = true
                })
                if (languagePicker) io.github.localledger.app.ui.LanguagePicker(AppLanguages.selected(this),
                    onSelect = { languagePicker = false; AppLanguages.apply(this, it) }, onDismiss = { languagePicker = false })
            }
        }
    }
    override fun onResume() {
        super.onResume()
        ledgerGraph.activityVisible = true
        ledgerGraph.dismissOverlay?.invoke()
        model.resume()
        model.updates.check()
    }
    override fun onPause() { ledgerGraph.activityVisible = false; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeQuickIntent(intent) }
    private fun consumeQuickIntent(intent: Intent?) {
        if (intent?.action == LedgerNotifications.NEW_ENTRY) { ledgerGraph.requestQuickEntry(); intent.action = Intent.ACTION_MAIN }
    }
}
