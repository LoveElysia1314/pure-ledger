package io.github.localledger.app

import android.app.Activity
import android.content.Context
import android.os.Looper
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=LedgerApplication::class, qualifiers="en-rUS")
class AppLanguagesTest {
    @Test fun allFourLanguagesHaveEveryMessageAndMatchingFormatArguments() {
        fun messages(directory:String):Map<String,String> {
            val rows=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$directory/strings.xml")).getElementsByTagName("string")
            return (0 until rows.length).associate { rows.item(it).attributes.getNamedItem("name").nodeValue to rows.item(it).textContent }
        }
        val base=messages("values");val args=Regex("%[0-9]+\\\$s|%%")
        for(directory in listOf("values-b+zh+Hans","values-b+zh+Hant","values-en","values-ja")) {
            val translated=messages(directory);assertEquals(directory,base.keys,translated.keys)
            base.forEach { (key,value) ->
                assertTrue(key,translated.getValue(key).isNotBlank())
                assertEquals("$directory $key",args.findAll(value).map{it.value}.sorted().toList(),args.findAll(translated.getValue(key)).map{it.value}.sorted().toList())
            }
        }
        val context=RuntimeEnvironment.getApplication() as LedgerApplication;AppStrings.initialize(context)
        assertEquals("纯净记账",AppStrings.inLocale(Locale.forLanguageTag("zh-Hans"),R.string.app_name))
        assertEquals("純淨記帳",AppStrings.inLocale(Locale.forLanguageTag("zh-Hant"),R.string.app_name))
        assertEquals("ピュア家計簿",AppStrings.inLocale(Locale.JAPANESE,R.string.app_name))
        assertEquals("儲存",AppStrings.inLocale(Locale.forLanguageTag("zh-Hant"),R.string.ui_save))
    }
    @Test fun chineseScriptsRegionsAndTheDeclaredSystemListMatchTheFourChoices() {
        for(tag in listOf("zh","zh-CN","zh-SG","zh-Hans","zh-Hans-TW")) assertEquals(AppLanguage.SIMPLIFIED,AppLanguage.from(Locale.forLanguageTag(tag)))
        for(tag in listOf("zh-TW","zh-HK","zh-Hant")) assertEquals(AppLanguage.TRADITIONAL,AppLanguage.from(Locale.forLanguageTag(tag)))
        assertEquals(AppLanguage.ENGLISH,AppLanguage.from(Locale.US));assertEquals(AppLanguage.JAPANESE,AppLanguage.from(Locale.JAPAN))
        assertNull(AppLanguage.from(Locale.FRENCH))
        val rows=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/locales_config.xml")).getElementsByTagName("locale")
        assertEquals(AppLanguage.entries.map{it.tag}.toSet(),(0 until rows.length).map{rows.item(it).attributes.getNamedItem("android:name").nodeValue}.toSet())
    }
    @Test fun returningToSystemAlsoClearsAnOldUnsupportedSystemAppLocale() {
        val app=RuntimeEnvironment.getApplication() as LedgerApplication
        val manager=app.getSystemService(android.app.LocaleManager::class.java)
        val original=manager.applicationLocales
        val controller=Robolectric.buildActivity(LanguageTestActivity::class.java).setup()
        try {
            manager.applicationLocales=android.os.LocaleList.forLanguageTags("fr")
            assertNull(AppLanguages.selected(app))
            AppLanguages.apply(controller.get(),null)
            assertTrue(manager.applicationLocales.isEmpty)
        } finally { manager.applicationLocales=original;controller.pause().stop().destroy() }
    }
    @Test @Config(sdk=[32]) fun legacySelectionPersistsAcrossContextsAndCanReturnToSystem() {
        val app=RuntimeEnvironment.getApplication() as LedgerApplication
        val controller=Robolectric.buildActivity(LanguageTestActivity::class.java).setup()
        val activity=controller.get()
        try {
            AppLanguages.apply(activity,AppLanguage.JAPANESE);shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AppLanguage.JAPANESE,AppLanguages.selected(app))
            assertEquals("ピュア家計簿",AppLanguages.wrap(app).getString(R.string.app_name))
            AppLanguages.apply(activity,AppLanguage.TRADITIONAL);shadowOf(Looper.getMainLooper()).idle()
            assertEquals("純淨記帳",AppLanguages.wrap(app).getString(R.string.app_name))
            AppLanguages.apply(activity,null);shadowOf(Looper.getMainLooper()).idle()
            assertNull(AppLanguages.selected(app))
            assertEquals(android.content.res.Resources.getSystem().configuration.locales,AppLanguages.wrap(app).resources.configuration.locales)
        } finally { app.getSharedPreferences("preferences",Context.MODE_PRIVATE).edit().remove("app_language_legacy").commit();app.graph.close();controller.pause().stop().destroy();AppStrings.initialize(app) }
    }
}
class LanguageTestActivity : Activity()
