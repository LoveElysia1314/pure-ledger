package io.github.localledger.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.*

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class CategoryStyleTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-category-style.db"
    @Before fun prepare() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); context.deleteDatabase("synthetic-style-target.db"); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name); context.deleteDatabase("synthetic-style-target.db") }
    @Test fun renameAndHideKeepIndependentIconAndExistingLedgerAndSuggestionsStopUsingHiddenCategory() {
        val category = store.snapshot().catalog.categories.first { it.name == "三餐" }
        store.saveCategory( category.type, "午餐", category.id, "wifi")
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(123), "合成商户", "synthetic-style-001", null, "v1", 1000)
        store.saveCandidate(ConfirmedEntry(source, categoryId = category.id), "request")
        val before = store.snapshot()
        store.setCategoryHidden(category.id, true)
        store.saveCategory( category.type, "餐饮", category.id)
        store.close(); store = LedgerStore(context, name)
        val after = store.snapshot(); val saved = after.catalog.categories.single { it.id == category.id }
        assertEquals("wifi", saved.icon); assertTrue(saved.hidden); assertEquals(category.position, saved.position)
        assertEquals(before.rows, after.rows); assertEquals(before.expense, after.expense)
        assertNull(store.prepareCandidate(source).draft.categoryId)
        store.setCategoryHidden(category.id, false)
        assertEquals(category.id, store.prepareCandidate(source).draft.categoryId)
    }
    @Test fun reorderIsScopedAndStaleOrFailedUpdatesRollBack() {
        val catalog = store.snapshot().catalog
        val expenses = catalog.categories.filter { it.type == TransactionType.EXPENSE }.map { it.id }
        val incomes = catalog.categories.filter { it.type == TransactionType.INCOME }
        store.reorderCategories( TransactionType.EXPENSE, expenses.reversed())
        fun order() = store.snapshot().catalog.categories.filter { it.type == TransactionType.EXPENSE }.map { it.id }
        assertEquals(expenses.reversed(), order()); assertEquals(incomes, store.snapshot().catalog.categories.filter { it.type == TransactionType.INCOME })
        try { store.reorderCategories( TransactionType.EXPENSE, expenses.drop(1)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(expenses.reversed(), order())
        store.writableDatabase.execSQL("CREATE TRIGGER fail_order BEFORE UPDATE OF position ON categories WHEN NEW.position=1 BEGIN SELECT RAISE(ABORT,'synthetic order fault'); END")
        try { store.reorderCategories( TransactionType.EXPENSE, expenses); fail() } catch (_: android.database.SQLException) { }
        assertEquals(expenses.reversed(), order())
    }
    private fun legacyV4(failUpgrade: Boolean) {
        store.close(); context.deleteDatabase(name)
        object : SQLiteOpenHelper(context, name, null, 4) {
            override fun onCreate(db: SQLiteDatabase) {
                LedgerSchema.create(db)
                db.execSQL("DROP TABLE categories")
                db.execSQL("CREATE TABLE categories(id TEXT PRIMARY KEY NOT NULL,book_id TEXT NOT NULL REFERENCES books(id),type TEXT NOT NULL,name TEXT NOT NULL,UNIQUE(book_id,type,name))")
                db.execSQL("INSERT INTO categories VALUES('legacy-cat',?,'EXPENSE','三餐')", arrayOf(DEFAULT_BOOK))
                db.execSQL("INSERT INTO entries VALUES(7,'legacy-entry','EXPENSE',123,'合成账目',1000,'保留备注',1001,1002,?,'legacy-cat',NULL,NULL,0,0,0,'MANUAL','{}',2,0)", arrayOf(DEFAULT_BOOK))
                if (failUpgrade) db.execSQL("CREATE TRIGGER fail_style BEFORE UPDATE ON categories BEGIN SELECT RAISE(ABORT,'synthetic style fault'); END")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("fixture")
        }.use { it.writableDatabase }
        store = LedgerStore(context, name)
    }
    @Test fun realV4SchemaUpgradesWithoutChangingEntryOrReference() {
        legacyV4(false)
        val snapshot = store.snapshot()
        assertEquals(9, store.readableDatabase.version); assertEquals(1, snapshot.count)
        val row = snapshot.rows.single(); assertEquals(7L, row.id); assertEquals(123L, row.amount.minor)
        assertEquals("legacy-cat", row.categoryId); assertEquals("保留备注", row.note); assertEquals(2L, row.revision)
        val category = snapshot.catalog.categories.single()
        assertEquals("legacy-cat", category.id); assertEquals("meal", category.icon); assertEquals(0L, category.position); assertFalse(category.hidden)
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
    }
    @Test fun failedV4StyleMigrationLeavesVersionSchemaAndEntryUntouched() {
        legacyV4(true)
        try { store.snapshot(); fail() } catch (_: android.database.SQLException) { }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(4, db.version)
            db.rawQuery("PRAGMA table_info(categories)", null).use { c -> while (c.moveToNext()) assertNotEquals("icon", c.getString(1)) }
            db.rawQuery("SELECT category_id,amount_minor FROM entries WHERE id=7", null).use { it.moveToFirst(); assertEquals("legacy-cat", it.getString(0)); assertEquals(123L, it.getLong(1)) }
        }
    }
    private fun oldExchange(bytes: ByteArray): ByteArray {
        val members = mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; members[e.name] = zip.readBytes() } }
        val records = JSONObject(members.getValue("records.json").toString(Charsets.UTF_8))
        val categories = records.getJSONArray("categories")
        repeat(categories.length()) { index -> categories.getJSONObject(index).apply { remove("icon"); remove("position"); remove("hidden"); remove("presetKey");put("bookId",DEFAULT_BOOK) } }
        val accounts = records.getJSONArray("accounts")
        repeat(accounts.length()) { index -> accounts.getJSONObject(index).apply { remove("archived"); put("bookId", DEFAULT_BOOK) } }
        val content = records.toString().toByteArray(Charsets.UTF_8)
        members["records.json"] = content
        members["manifest.json"] = JSONObject(members.getValue("manifest.json").toString(Charsets.UTF_8)).put("schemaVersion",1).put("bytes",content.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }).toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> members.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } } }.toByteArray()
    }
    @Test fun newExchangeKeepsStylesAndOldExchangeDoesNotOverwriteLocalStyle() {
        val category = store.snapshot().catalog.categories.first()
        store.saveCategory( category.type, category.name, category.id, "camera", true)
        val expenses = store.snapshot().catalog.categories.filter { it.type == TransactionType.EXPENSE }.map { it.id }
        store.reorderCategories( TransactionType.EXPENSE, expenses.reversed())
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(123), "合成记录", 1000, categoryId = category.id))
        val bytes = store.exportExchange(); val data = LedgerExchange.decode(bytes)
        assertEquals(5, data.schemaVersion)
        LedgerStore(context, "synthetic-style-target.db").use { target ->
            target.commitExchange(data)
            assertEquals(store.snapshot().catalog.categories, target.snapshot().catalog.categories)
            assertEquals(1, target.commitExchange(data).skipped)
        }
        val old = LedgerExchange.decode(oldExchange(bytes))
        assertEquals(1, old.schemaVersion); assertTrue(store.previewExchange(old).conflicts.isEmpty())
        store.commitExchange(old)
        assertEquals("camera", store.snapshot().catalog.categories.single { it.id == category.id }.icon)
        assertTrue(store.snapshot().catalog.categories.single { it.id == category.id }.hidden)
    }
    @Test fun customizedUnusedStockIsNotDiscardedDuringExchange() {
        val original = LedgerExchange.decode(store.exportExchange())
        val categories = store.snapshot().catalog.categories
        store.reorderCategories( TransactionType.EXPENSE, categories.filter { it.type == TransactionType.EXPENSE }.map { it.id }.reversed())
        assertFalse(store.previewExchange(original).conflicts.isEmpty())
        try { store.commitExchange(original); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(categories.first { it.type == TransactionType.EXPENSE }.id, store.snapshot().catalog.categories.filter { it.type == TransactionType.EXPENSE }.last().id)
    }
}
