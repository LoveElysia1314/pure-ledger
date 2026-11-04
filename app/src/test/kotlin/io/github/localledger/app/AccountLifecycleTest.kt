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
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class AccountLifecycleTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-account-lifecycle.db"
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); context.deleteDatabase("synthetic-account-copy.db"); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name); context.deleteDatabase("synthetic-account-copy.db") }
    private fun packet() = LedgerExchange.decode(store.exportExchange())
    private fun foreignKeys() { store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) } }

    @Test fun deletingUsedAccountsPreservesFinancialRowsAndHistoricalEditingButNotNewSelection() {
        val cash = store.saveAccount("现金", BigInteger("10000"))
        val bank = store.saveAccount("银行卡", BigInteger.ZERO)
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(123), "合成商户", "account-source", null, "test", 1000)
        store.saveCandidate(ConfirmedEntry(source, accountId = cash), "recognized")
        val transfer = store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(500), "", 2, accountId = cash, toAccountId = bank, fee = Money(2)), "transfer")
        val deleted = store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(10), "合成旧账", 3, accountId = cash), "deleted")
        store.deleteEntry(deleted, store.entry(deleted)!!.revision)
        val before = packet(); val balance = store.snapshot().catalog.accounts.single { it.id == cash }.balanceMinor
        store.deleteCatalog("accounts", cash)
        val after = packet(); val catalog = store.snapshot().catalog
        assertEquals(before.entries, after.entries); assertEquals(before.recognition, after.recognition)
        assertEquals(listOf(bank), catalog.activeAccounts.map { it.id })
        assertEquals(balance, catalog.accounts.single { it.id == cash }.balanceMinor)
        assertTrue(catalog.accounts.single { it.id == cash }.archived)
        assertEquals(catalog.accounts, catalog.entryAccounts(cash))
        assertNotEquals(cash, store.prepareCandidate(source.copy(externalId = "next")).draft.accountId)
        store.snapshot().selection.recent.forEach { assertNotEquals(cash, it.choice.accountId); assertNotEquals(cash, it.choice.toAccountId) }
        val old = store.entry(transfer)!!
        store.updateEntry(old.id, old.revision, old.draft().copy(note = "修改旧账备注"))
        assertEquals(cash, store.entry(transfer)!!.accountId)
        try { store.createEntry(old.draft()); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(2, store.snapshot().count); foreignKeys()
    }

    @Test fun restoreChecksActiveNamesAndCanFollowAnExplicitRename() {
        val old = store.saveAccount("现金", BigInteger("100"))
        store.deleteCatalog("accounts", old)
        val newer = store.saveAccount("现金", BigInteger("200"))
        val before = packet()
        try { store.restoreAccount(old); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("同名")) }
        assertEquals(before, packet())
        store.saveAccount("旧现金", null, old); store.restoreAccount(old)
        assertEquals(setOf(old, newer), store.snapshot().catalog.activeAccounts.map { it.id }.toSet())
        assertEquals(BigInteger("100"), store.snapshot().catalog.accounts.single { it.id == old }.balanceMinor)
        foreignKeys()
    }

    @Test fun archivedAccountsAndTheirHistoryRoundTripWithDuplicateDisplayNames() {
        val old = store.saveAccount("现金", BigInteger("1000"))
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(5), "合成", 1, accountId = old))
        store.deleteCatalog("accounts", old); store.saveAccount("现金", BigInteger("2000"))
        val data = packet(); assertEquals(5, data.schemaVersion)
        LedgerStore(context, "synthetic-account-copy.db").use { copy ->
            copy.commitExchange(data)
            assertEquals(store.snapshot().catalog, copy.snapshot().catalog)
            assertEquals(data.entries.map { it.copy(id=0) }, LedgerExchange.decode(copy.exportExchange()).entries.map { it.copy(id=0) })
            assertEquals(0, copy.commitExchange(data).added)
        }
    }

    @Test fun failedDeletionRollsBackArchiveAndRememberedChoices() {
        val id = store.saveAccount("现金", null)
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(5), "合成", 1, accountId = id))
        val before = store.snapshot(); val data = packet()
        store.writableDatabase.execSQL("CREATE TRIGGER fail_archive BEFORE UPDATE ON selection_recent BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        try { store.deleteCatalog("accounts", id); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.snapshot()); assertEquals(data, packet()); foreignKeys()
    }

    private fun legacyV7(failUpgrade: Boolean = false) {
        store.close(); context.deleteDatabase(name)
        val preset = CategoryPresets.legacy.first()
        val presetId = java.util.UUID.nameUUIDFromBytes("category-pack-v1\u0000$DEFAULT_BOOK\u0000${preset.type}\u0000${preset.name}".toByteArray(Charsets.UTF_8)).toString()
        object : SQLiteOpenHelper(context, name, null, 7) {
            override fun onCreate(db: SQLiteDatabase) {
                LedgerSchema.catalog(db)
                db.execSQL("DROP TABLE accounts")
                db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL UNIQUE,opening_minor TEXT NOT NULL,balance_known INTEGER NOT NULL)")
                db.execSQL("DROP TABLE categories")
                db.execSQL("CREATE TABLE categories(id TEXT PRIMARY KEY NOT NULL,book_id TEXT NOT NULL REFERENCES books(id),type TEXT NOT NULL,name TEXT NOT NULL,icon TEXT NOT NULL,position INTEGER NOT NULL,hidden INTEGER NOT NULL,UNIQUE(book_id,type,name))")
                LedgerSchema.entries(db, "entries"); LedgerSchema.receipts(db, "recognition_receipts", "entries"); LedgerSchema.imports(db); SelectionSchema.create(db)
                db.execSQL("INSERT INTO accounts VALUES('a','现金','9007199254740991000',1)")
                db.execSQL("INSERT INTO categories VALUES(?,?,'EXPENSE','午餐','cup',7,1)", arrayOf(presetId, DEFAULT_BOOK))
                db.execSQL("INSERT INTO categories VALUES('custom',?,'EXPENSE','三餐','gift',2,0)", arrayOf(DEFAULT_BOOK))
                db.execSQL("INSERT INTO entries VALUES(7,'v7-bill','EXPENSE',123,'合成',1000,'备注',100,200,?,?,'a',NULL,0,0,1,'MANUAL','{}',4,0)", arrayOf(DEFAULT_BOOK,presetId))
                if (failUpgrade) db.execSQL("CREATE TRIGGER fail_origins BEFORE UPDATE ON categories BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.use { it.writableDatabase }
        store = LedgerStore(context, name)
    }
    @Test fun actualV7UpgradeRetainsIdsValuesReferencesAndBackfillsOnlyKnownPresetIds() {
        legacyV7()
        val preset = CategoryPresets.legacy.first(); val presetId = java.util.UUID.nameUUIDFromBytes("category-pack-v1\u0000$DEFAULT_BOOK\u0000${preset.type}\u0000${preset.name}".toByteArray(Charsets.UTF_8)).toString()
        val snapshot = store.snapshot(); val row = snapshot.rows.single()
        assertEquals(9, store.readableDatabase.version); assertEquals(7L, row.id); assertEquals("v7-bill", row.uid); assertEquals(4L, row.revision)
        assertEquals("a", row.accountId); assertEquals(BigInteger("9007199254740990877"), snapshot.catalog.accounts.single().balanceMinor)
        assertFalse(snapshot.catalog.accounts.single().archived)
        assertEquals(preset.key, snapshot.catalog.categories.single { it.id == presetId }.presetKey)
        assertTrue(snapshot.catalog.categories.any { it.presetKey=="INCOME:AA" })
        assertTrue(snapshot.catalog.categories.any { it.presetKey=="INCOME:稿费" })
        assertTrue(snapshot.catalog.categories.any { it.presetKey=="INCOME:利息" })
        assertNull(snapshot.catalog.categories.single { it.id == "custom" }.presetKey)
        store.deleteCatalog("accounts", "a"); store.saveAccount("现金", null)
        foreignKeys()
    }
    @Test fun version3ExchangeIsStillReadableWithPresetOriginsAndActiveAccounts() {
        store.saveAccount("现金", null)
        val original = packet()
        val members = mutableMapOf<String,ByteArray>()
        java.util.zip.ZipInputStream(store.exportExchange().inputStream()).use { zip -> while(true) { val e=zip.nextEntry ?: break; members[e.name]=zip.readBytes() } }
        val root = org.json.JSONObject(members.getValue("records.json").toString(Charsets.UTF_8))
        root.getJSONArray("accounts").let { a -> repeat(a.length()) { a.getJSONObject(it).remove("archived") } }
        root.getJSONArray("categories").let { a -> repeat(a.length()) { a.getJSONObject(it).apply { remove("presetKey");put("bookId",DEFAULT_BOOK) } } }
        val records = root.toString().toByteArray(Charsets.UTF_8)
        members["records.json"] = records
        members["manifest.json"] = org.json.JSONObject(members.getValue("manifest.json").toString(Charsets.UTF_8)).put("schemaVersion",3).put("bytes", records.size)
            .put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(records).joinToString("") { "%02x".format(it) }).toString().toByteArray(Charsets.UTF_8)
        val bytes = java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { zip -> members.forEach { (n,b) -> zip.putNextEntry(java.util.zip.ZipEntry(n)); zip.write(b); zip.closeEntry() } } }.toByteArray()
        val old = LedgerExchange.decode(bytes)
        assertEquals(original.accounts,old.accounts);assertEquals(original.categories.map{it.copy(presetKey=null)},old.categories)
        assertTrue(store.previewExchange(old).conflicts.isEmpty()); assertEquals(0,store.commitExchange(old).added)
    }
    @Test fun failedV7UpgradeRollsBackAccountReplacementAndCategoryProvenanceTogether() {
        legacyV7(failUpgrade = true)
        try { store.snapshot(); fail() } catch (_: android.database.SQLException) { }
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(7, db.version)
            val columns = db.rawQuery("PRAGMA table_info(accounts)",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(1)) } }
            assertFalse("archived" in columns)
            db.rawQuery("SELECT account_id,amount_minor,revision FROM entries WHERE id=7", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("a", c.getString(0)); assertEquals(123L,c.getLong(1)); assertEquals(4L,c.getLong(2)) }
            db.rawQuery("SELECT name,opening_minor FROM accounts WHERE id='a'",null).use { c -> assertTrue(c.moveToFirst()); assertEquals("现金",c.getString(0)); assertEquals("9007199254740991000",c.getString(1)) }
        }
    }
    @Test fun excelImportReusesActiveNamesButDoesNotAttachNewBillsToDeletedAccounts() {
        val old = store.saveAccount("现金", null)
        store.deleteCatalog("accounts", old)
        val document = io.github.localledger.core.migration.LegacyWorkbook.parse(workbookFixture(listOf("1.23")))
        store.commitImport(document)
        val active = store.snapshot().catalog.activeAccounts.single()
        assertEquals("现金",active.name); assertNotEquals(old,active.id)
        assertEquals(active.id,store.snapshot().rows.single().accountId)
        assertEquals(0,store.commitImport(document).added)
        assertEquals(2,store.snapshot().catalog.accounts.size)
    }
}
