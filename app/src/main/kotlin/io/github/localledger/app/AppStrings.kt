package io.github.localledger.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableIntStateOf
import java.util.Locale

/** Application resources also serve service-owned overlays and non-composable labels. */
internal object AppStrings {
    private lateinit var context: Context
    private val revision = mutableIntStateOf(0)
    fun initialize(value: Context) { context = value; changed() }
    fun changed() { revision.intValue++ }
    val locale: Locale get() { revision.intValue; return context.resources.configuration.locales[0] ?: Locale.SIMPLIFIED_CHINESE }
    fun inLocale(locale: Locale, @StringRes id: Int): String = context.createConfigurationContext(
        android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) }).getString(id)
    fun get(@StringRes id: Int, vararg arguments: Any?): String {
        revision.intValue // Reading configuration state also invalidates Compose callers in a service window.
        return context.getString(id, *arguments)
    }
}
internal fun appString(@StringRes id: Int, vararg arguments: Any?): String = AppStrings.get(id, *arguments)
