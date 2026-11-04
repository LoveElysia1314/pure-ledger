package io.github.localledger.app

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Native names stay recognizable whichever language is currently selected. */
internal enum class AppLanguage(val tag: String, val nativeName: String) {
    SIMPLIFIED("zh-Hans", "简体中文"), TRADITIONAL("zh-Hant", "繁體中文"),
    ENGLISH("en", "English"), JAPANESE("ja", "日本語");
    companion object {
        fun from(locale: Locale): AppLanguage? = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.script != "Hans" && locale.country in setOf("TW", "HK", "MO")) TRADITIONAL else SIMPLIFIED
            "en" -> ENGLISH
            "ja" -> JAPANESE
            else -> null
        }
    }
}

internal object AppLanguages {
    private const val KEY = "app_language_legacy"
    private fun preferences(context: Context) = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    fun selected(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) null else AppLanguage.from(locales[0])
        }
        val tag = preferences(context).getString(KEY, null)
        return AppLanguage.entries.firstOrNull { it.tag == tag }
    }
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val config = Configuration(context.resources.configuration)
        val language = selected(context)
        config.setLocales(if (language == null) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(language.tag))
        return context.createConfigurationContext(config)
    }
    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = preferences(context)
            val legacy = prefs.getString(KEY, null)
            if (legacy != null) {
                val manager = context.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty && AppLanguage.entries.any { it.tag == legacy })
                    manager.applicationLocales = LocaleList.forLanguageTags(legacy)
                prefs.edit().remove(KEY).apply()
            }
        }
    }
    fun apply(activity: Activity, language: AppLanguage?) {
        if (selected(activity) == language) {
            if (language != null) return
            if (Build.VERSION.SDK_INT >= 33 && activity.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty) return
            if (Build.VERSION.SDK_INT < 33 && !preferences(activity).contains(KEY)) return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        } else {
            preferences(activity).edit().apply { if (language == null) remove(KEY) else putString(KEY, language.tag) }.apply()
            AppStrings.initialize(wrap(activity.application))
            activity.ledgerGraph.refreshLocalizedNotification()
            activity.recreate()
        }
    }
}
