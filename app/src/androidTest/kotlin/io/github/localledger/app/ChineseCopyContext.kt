package io.github.localledger.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Keep existing Chinese UI goldens and the reference AVD's English system widgets independent. */
internal fun chineseCopyContext(context: Context): Context = context.createConfigurationContext(
    Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
