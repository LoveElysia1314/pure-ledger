package io.github.localledger.app

import android.content.Context
import android.content.res.Configuration
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigInteger
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=LedgerApplication::class, qualifiers="en-rUS-w360dp-h800dp-xxhdpi")
class LocalizationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val name="synthetic-localization.db"
    @Before fun setup() { AppStrings.initialize(context); context.deleteDatabase(name) }
    @After fun close() { context.deleteDatabase(name); AppStrings.initialize(context) }
    @Test fun allResourceKeysAndFormatArgumentsMatchAndEveryPresetAndIconHasAnEnglishLabel() {
        fun messages(path: String): Map<String,String> {
            val nodes=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val zh=messages("src/main/res/values/strings.xml");val en=messages("src/main/res/values-en/strings.xml")
        assertEquals(zh.keys,en.keys)
        val args=Regex("%[0-9]+\\\$s")
        zh.forEach { (key,value) -> assertEquals(key,args.findAll(value).map { it.value }.sorted().toList(),args.findAll(en.getValue(key)).map { it.value }.sorted().toList()) }
        val han=Regex("[\u4e00-\u9fff]")
        en.forEach { (key,value) -> assertTrue(key,value.isNotBlank());assertFalse(key,han.containsMatchIn(value)) }
        CategoryPresets.known.forEach { assertFalse(han.containsMatchIn(CatalogLabels.preset(it))) }
        CategoryIcons.keys.forEach { assertFalse(han.containsMatchIn(CatalogLabels.icon(it))) }
        assertEquals("English",AppStrings.locale.displayLanguage)
        assertEquals("Settings",appString(R.string.ui_settings))
    }
    @Test fun languageChangesKeepSavedNamesMoneyNotesIdsAndExportReferencesUnchanged() {
        LedgerStore(context,name).use { store ->
            val book=store.saveBook("我的账本");val account=store.saveAccount("微信钱包",BigInteger("10000"))
            val custom=store.saveCategory(TransactionType.EXPENSE,"自定义早餐",icon="meal")
            store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(1234),"合成商户",1000,"中文 note",book,custom,account),"locale-uid")
            val before=LedgerExchange.decode(store.exportExchange());val balances=store.snapshot().catalog.accounts
            AppStrings.initialize(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }))
            assertEquals("设置",appString(R.string.ui_settings))
            assertEquals(before,LedgerExchange.decode(store.exportExchange()))
            assertEquals(balances,store.snapshot().catalog.accounts)
            assertEquals("自定义早餐",store.snapshot().catalog.categories.single { it.id==custom }.name)
        }
    }
    @Test fun newEnglishPresetsRestoreByStableOriginAndNeverRenameCustomCategories() {
        LedgerStore(context,name).use { store ->
            val catalog=store.snapshot().catalog
            assertEquals("Default ledger",catalog.books.single().name)
            val meal=catalog.categories.single { it.presetKey=="EXPENSE:三餐" }
            assertEquals("Meals",meal.name)
            store.saveCategory(TransactionType.EXPENSE,"Lunch",meal.id,"cup",true)
            val custom=store.saveCategory(TransactionType.EXPENSE,"Meals",icon="gift",hidden=true)
            val before=store.snapshot().catalog.categories.single { it.id==custom }
            store.restoreCategoryDefaults(TransactionType.EXPENSE)
            assertEquals("Meals",store.snapshot().catalog.categories.single { it.id==meal.id }.name)
            assertEquals(before,store.snapshot().catalog.categories.single { it.id==custom })
            val packet=LedgerExchange.decode(store.exportExchange())
            val copyName="synthetic-localization-copy.db";context.deleteDatabase(copyName)
            try { LedgerStore(context,copyName).use { copy -> assertTrue(copy.previewExchange(packet).conflicts.isEmpty());copy.commitExchange(packet);assertEquals(store.snapshot().catalog,copy.snapshot().catalog) } }
            finally { context.deleteDatabase(copyName) }
        }
    }
    @Test fun validationMessagesUseEnglishAndKeepUnknownFieldsAndIdentifiersIntact() {
        assertEquals("Account deleted. Choose another account.",localizedMessage("账户已删除，请重新选择"))
        assertEquals("Entry user-id-123 has conflicting content",localizedMessage("账单 user-id-123 内容冲突"))
        assertEquals("Field 自定义列 must contain text",localizedMessage("字段 自定义列 需要文字值"))
        assertEquals("An unknown message",localizedMessage("An unknown message"))
        assertEquals("Salary",CatalogLabels.preset(CategoryPresets.legacy.single { it.name=="工资" }))
    }

    @Test fun existingPresetNamesFollowAllFourLocalesWithoutChangingStoredNamesOrReceipts() {
        fun language(locale: Locale) = AppStrings.initialize(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }))
        language(Locale.SIMPLIFIED_CHINESE)
        LedgerStore(context,name).use { store ->
            val catalog=store.snapshot().catalog
            val meals=catalog.categories.single { it.presetKey=="EXPENSE:三餐" }
            val transport=catalog.categories.single { it.presetKey=="EXPENSE:交通" }
            val other=catalog.categories.single { it.presetKey=="EXPENSE:其他支出" }
            store.saveCategory(TransactionType.EXPENSE,"午饭专用",meals.id)
            val custom=store.saveCategory(TransactionType.EXPENSE,"自定义交通")
            store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(100),"公交",1000,bookId=DEFAULT_BOOK,categoryId=transport.id),"localized-transport")
            store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(200),"旧账",1000,bookId=DEFAULT_BOOK),"historical-null")
            store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(300),"其他",1000,bookId=DEFAULT_BOOK,categoryId=other.id),"other-entry")
            val original=LedgerExchange.decode(store.exportExchange())
            for ((locale, expected) in listOf(Locale.SIMPLIFIED_CHINESE to "交通",Locale.TRADITIONAL_CHINESE to "交通",Locale.ENGLISH to "Transport",Locale.JAPANESE to "交通")) {
                language(locale)
                val snapshot=store.snapshot()
                assertEquals(expected,snapshot.catalog.categories.single { it.id==transport.id }.displayName)
                assertEquals("午饭专用",snapshot.catalog.categories.single { it.id==meals.id }.displayName)
                assertEquals("自定义交通",snapshot.catalog.categories.single { it.id==custom }.displayName)
                assertEquals(1,store.snapshot(query=LedgerQuery(text=expected)).count)
                val others=snapshot.categories.single { it.name==CatalogLabels.other(TransactionType.EXPENSE) }
                assertEquals(2,others.count);assertEquals(BigInteger("500"),others.amount)
                assertEquals(original,LedgerExchange.decode(store.exportExchange()))
            }
        }
    }

    @Test fun traditionalAndJapaneseStockNamesRemainRecognizableAfterLanguageSwitching() {
        for (locale in listOf(Locale.TRADITIONAL_CHINESE,Locale.JAPANESE)) {
            AppStrings.initialize(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }))
            for (preset in CategoryPresets.known) {
                val category=LedgerCategory("preset",preset.type,CatalogLabels.preset(preset),presetKey=preset.key)
                AppStrings.initialize(context)
                assertEquals(CatalogLabels.preset(preset),category.displayName)
                AppStrings.initialize(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }))
            }
        }
    }
}
