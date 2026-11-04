package io.github.localledger.app

import android.content.res.Configuration
import java.util.Locale

class ChineseTestApplication : LedgerApplication() {
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("preferences", MODE_PRIVATE).edit().putBoolean("automatic_updates", false).commit()
        AppStrings.initialize(createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }))
    }
}
