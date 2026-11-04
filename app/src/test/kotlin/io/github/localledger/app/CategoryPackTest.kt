package io.github.localledger.app

import android.content.Context
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryPackTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-categories.db"
    private val restoredName = "synthetic-category-restore.db"
    private val expense = TransactionType.EXPENSE
    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); context.deleteDatabase(restoredName)
        store = LedgerStore(context, name)
    }
    @After fun close() { store.close(); context.deleteDatabase(name); context.deleteDatabase(restoredName) }
    private fun packet() = LedgerExchange.decode(store.exportExchange())
    private fun draft(book: String = DEFAULT_BOOK, category: String? = null) = EntryDraft(expense, Money(800), "合成商户", 1,
        bookId = book, categoryId = category, accountId = store.snapshot().catalog.activeAccounts.firstOrNull()?.id ?: store.saveAccount("合成现金", null))

    /** A valid version-4 archive has book-scoped category fields, not just an older version number. */
    private fun oldArchive(): ExchangeData {
        val members = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(store.exportExchange())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; members[entry.name] = zip.readBytes() }
        }
        val records = JSONObject(members.getValue("records.json").toString(Charsets.UTF_8))
        val categories = records.getJSONArray("categories")
        for (index in 0 until categories.length()) categories.getJSONObject(index).put("bookId", DEFAULT_BOOK)
        val bytes = records.toString().toByteArray(Charsets.UTF_8)
        val manifest = JSONObject(members.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("schemaVersion", 4).put("bytes", bytes.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8), "records.json" to bytes).forEach { (entry, data) ->
                zip.putNextEntry(ZipEntry(entry)); zip.write(data); zip.closeEntry()
            }
        }
        return LedgerExchange.decode(output.toByteArray())
    }

    @Test fun freshAndNewBooksReuseOneGlobalDailyDirectory() {
        val initial = store.snapshot()
        assertEquals(38, initial.catalog.categories.size)
        assertEquals(26, initial.catalog.categories.count { it.type == expense })
        assertEquals(12, initial.catalog.categories.count { it.type == TransactionType.INCOME })
        assertTrue(initial.catalog.categories.none { it.hidden })
        assertTrue(initial.catalog.accounts.isEmpty())
        assertTrue(CategoryPack.pristine(initial.rows, initial.catalog))
        val book = store.saveBook("旅行")
        assertEquals(initial.catalog.categories, store.snapshot().catalog.categories)
        store.deleteCatalog("books", book)
        assertEquals(initial.catalog.categories, store.snapshot().catalog.categories)
        val traffic = initial.catalog.categories.single { it.name == "交通" }
        assertEquals(traffic.id, store.saveCategory(traffic.type, traffic.name))
    }
    @Test fun deletingAnEmptyBookKeepsGlobalCategoriesAndAccountsButLiveBillsStayProtected() {
        val empty = store.saveBook("未使用")
        val account = store.saveAccount("钱包", null)
        val before = store.snapshot().catalog
        store.deleteCatalog("books", empty)
        assertEquals(before.categories, store.snapshot().catalog.categories)
        assertEquals(before.accounts, store.snapshot().catalog.accounts)
        assertTrue(store.snapshot().catalog.accounts.any { it.id == account })
        val withBill = store.saveBook("已有账单")
        val category = store.snapshot().catalog.categories.first { it.type == expense }
        store.createEntry(draft(withBill, category.id))
        val saved = store.snapshot()
        try { store.deleteCatalog("books", withBill); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(saved, store.snapshot())
    }
    @Test fun renamedGlobalCategoriesKeepBothBooksReferencesAndChoicesAfterRestart() {
        val category = store.snapshot().catalog.categories.single { it.name == "三餐" }
        val other = store.saveBook("旅行")
        val first = store.createEntry(draft(category = category.id))
        val second = store.createEntry(draft(other, category.id))
        store.saveCategory(expense, "午饭", category.id, icon = "cup", hidden = true)
        val choices = store.snapshot().selection
        val catalog = store.snapshot().catalog.categories
        store.close(); store = LedgerStore(context, name)
        assertEquals(catalog, store.snapshot().catalog.categories)
        assertEquals(choices, store.snapshot().selection)
        assertEquals(category.id, store.entry(first)!!.categoryId)
        assertEquals(category.id, store.entry(second)!!.categoryId)
        assertEquals("午饭", store.snapshot().catalog.categories.single { it.id == category.id }.name)
        assertFalse(store.snapshot().catalog.categories.any { it.name == "三餐" })
    }
    @Test fun deletedDefaultStaysDeletedAfterReopeningAndFailedRestorationRollsBack() {
        val category = store.snapshot().catalog.categories.single { it.name == "宠物" }
        store.deleteCatalog("categories", category.id)
        store.close(); store = LedgerStore(context, name)
        val before = packet()
        assertEquals(37, before.categories.size)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_categories BEFORE INSERT ON categories WHEN NEW.name='宠物' BEGIN SELECT RAISE(ABORT,'synthetic category fault'); END")
        try { store.restoreCategoryDefaults(expense); fail() } catch (_: android.database.SQLException) { }
        assertEquals(before, packet())
    }
    @Test fun mergingBooksMovesBillsWithoutReplacingTheirGlobalCategoryOrSettings() {
        val source = store.saveBook("旅行")
        val category = store.snapshot().catalog.categories.single { it.name == "三餐" }
        val id = store.createEntry(draft(source, category.id))
        store.saveCategory(expense, "午饭", category.id, icon = "cup", hidden = true)
        val before = packet()
        store.mergeBooks(store.previewBookMerge(source, DEFAULT_BOOK))
        val after = packet()
        assertEquals(before.categories, after.categories)
        assertEquals(before.accounts, after.accounts)
        assertEquals(category.id, store.entry(id)!!.categoryId)
        assertEquals(DEFAULT_BOOK, store.entry(id)!!.bookId)
        assertEquals(before.entries.single().amount, after.entries.single().amount)
    }
    @Test fun oldExchangeIdsRestoreIntoFreshDirectoryAndMergeWithoutReplacingCustomSettings() {
        store.writableDatabase.delete("categories", null, null)
        val category = store.saveCategory(expense, "交通")
        store.createEntry(draft(category = category))
        val source = packet()
        val old = oldArchive()
        assertEquals(4, old.schemaVersion)
        LedgerStore(context, restoredName).use { target ->
            assertTrue(target.previewExchange(old).conflicts.isEmpty()); target.commitExchange(old)
            assertEquals(source.categories, target.snapshot().catalog.categories)
            assertEquals(store.snapshot().catalog.accounts, target.snapshot().catalog.accounts)
            assertEquals(category, target.snapshot().rows.single().categoryId)
            assertEquals(0, target.commitExchange(old).added)
        }
        context.deleteDatabase(restoredName)
        LedgerStore(context, restoredName).use { target ->
            val custom = target.saveCategory(expense, "自有分类", icon = "heart", hidden = true)
            val keep = target.snapshot().catalog.categories.single { it.id == custom }
            assertTrue(target.previewExchange(old).conflicts.isEmpty())
            assertEquals(1, target.commitExchange(old).added)
            assertEquals(keep, target.snapshot().catalog.categories.single { it.id == custom })
            assertEquals(category, target.snapshot().rows.single().categoryId)
        }
    }
    @Test fun archiveWriteFailureRestoresTheFreshDirectoryAccountAndEmptyLedger() {
        store.writableDatabase.delete("categories", null, null)
        val category = store.saveCategory(expense, "交通")
        store.createEntry(draft(category = category))
        val old = oldArchive()
        LedgerStore(context, restoredName).use { target ->
            val before = LedgerExchange.decode(target.exportExchange())
            target.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON categories WHEN NEW.name='交通' BEGIN SELECT RAISE(ABORT,'synthetic restore fault'); END")
            try { target.commitExchange(old); fail() } catch (_: android.database.SQLException) { }
            assertEquals(before, LedgerExchange.decode(target.exportExchange()))
            assertEquals(38, target.snapshot().catalog.categories.size)
            assertEquals(0, target.snapshot().count)
        }
    }
}
