package io.github.localledger.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import io.github.localledger.core.migration.LegacyWorkbook
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionStoreTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-selections.db"
    private val targetName = "synthetic-selection-target.db"
    private val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1800), "合成咖啡店", "synthetic-selection-1", null, "v1", 123)
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); context.deleteDatabase(targetName); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name); context.deleteDatabase(targetName) }
    private fun repeatable(account: String? = null) = ConfirmedEntry(source, accountId = account)
    @Test fun eachConfirmedPopupCanAddTheSameTransactionWhileRetriesAreIdempotent() {
        val account = store.saveAccount("现金", BigInteger.valueOf(10000))
        val first = store.saveCandidate(repeatable(account), "request-1")
        assertEquals(first, store.saveCandidate(repeatable(account), "request-1"))
        assertTrue(store.prepareCandidate(source).repeated)
        store.saveCandidate(repeatable(account), "request-2")
        assertEquals(2, store.snapshot().count)
        assertEquals(BigInteger.valueOf(6400), store.snapshot().catalog.accounts.single().balanceMinor)
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM recognition_receipts", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        val rows = store.snapshot().rows.associateBy { it.uid }
        assertEquals("request-1", JSONObject(rows.getValue("request-2").rawSource).getString("duplicate_of_uid"))
        assertEquals(1800L, rows.getValue("request-1").amount.minor)
    }
    @Test fun changedSourceWithAnExistingNumberCanBeSavedWithoutOverwritingEvidence() {
        store.saveCandidate(repeatable(), "request-1")
        val changed = source.copy(amount = Money(1900))
        store.saveCandidate(ConfirmedEntry(changed), "request-2")
        assertEquals(listOf(1800L, 1900L), store.snapshot().rows.map { it.amount.minor }.sorted())
        assertEquals(SaveResult.AlreadySaved, store.existing(source)); assertEquals(SaveResult.Conflict, store.existing(changed))
        assertEquals(changed.evidenceHash(), JSONObject(store.snapshot().rows.first { it.uid == "request-2" }.rawSource).getString("evidence_hash"))
    }
    @Test fun aChangedRetryCannotOverwriteItsCommittedEntry() {
        store.saveCandidate(repeatable(), "request-1")
        try { store.saveCandidate(repeatable().copy(amount = Money(1900)), "request-1"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.snapshot().count); assertEquals(1800L, store.snapshot().rows.single().amount.minor)
    }
    @Test fun merchantChoicesAreIsolatedByAppMerchantAndDirectionAndPersistAfterReopen() {
        val book = store.saveBook("旅行")
        val account = store.saveAccount("旅费", null)
        val category = store.saveCategory( TransactionType.EXPENSE, "餐饮")
        store.saveCandidate(ConfirmedEntry(source, bookId = book, accountId = account, categoryId = category), "request-1")
        store.close(); store = LedgerStore(context, name)
        val match = store.prepareCandidate(source.copy(externalId = "synthetic-selection-2")).draft
        assertEquals(book, match.bookId); assertEquals(account, match.accountId); assertEquals(category, match.categoryId)
        store.setCurrentBook(DEFAULT_BOOK)
        assertEquals(DEFAULT_BOOK, store.prepareCandidate(source).draft.bookId)
        store.setCurrentBook(book)
        assertEquals(book, store.prepareCandidate(source.copy(merchant = "其他商户")).draft.bookId)
        assertEquals(book, store.prepareCandidate(source.copy(appPackage = "example.other")).draft.bookId)
        assertEquals(book, store.prepareCandidate(source.copy(type = TransactionType.INCOME)).draft.bookId)
    }
    @Test fun accountAndCategoryDefaultsComeFromConfirmedUseAndPreparationIsReadOnly() {
        val cash = store.saveAccount("现金", null)
        val bank = store.saveAccount("银行卡", null)
        val category = store.saveCategory( TransactionType.EXPENSE, "餐饮")
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(10), "", 1, categoryId = category, accountId = cash))
        store.createEntry(EntryDraft(TransactionType.INCOME, Money(20), "", 2, accountId = bank))
        val before = store.snapshot()
        val prepared = store.prepareCandidate(source).draft
        assertEquals(bank, prepared.accountId); assertEquals(category, prepared.categoryId)
        assertEquals(before, store.snapshot()); assertTrue(store.merchantChoices().isEmpty())
    }
    @Test fun deletingAMerchantMemoryDoesNotDeleteEntriesOrChangeBalances() {
        store.saveCandidate(repeatable(), "request-1")
        val before = store.snapshot()
        store.forgetMerchant(store.merchantChoices().single())
        assertTrue(store.merchantChoices().isEmpty()); assertEquals(before, store.snapshot())
    }
    @Test fun memoryWriteFailureRollsBackEntryReceiptAndRecentChoices() {
        store.writableDatabase.execSQL("CREATE TRIGGER reject_memory BEFORE INSERT ON merchant_choices BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { store.saveCandidate(repeatable(), "request-1"); fail() } catch (_: android.database.SQLException) { }
        assertEquals(0, store.snapshot().count); assertTrue(store.snapshot().selection.recent.isEmpty()); assertTrue(store.merchantChoices().isEmpty())
        assertNull(store.existing(source))
        store.writableDatabase.execSQL("DROP TRIGGER reject_memory")
        store.saveCandidate(repeatable(), "request-1"); assertEquals(1, store.snapshot().count)
    }
    @Test fun historicalImportDoesNotTeachDefaultsAndDuplicateRowsSurviveExchange() {
        store.commitImport(LegacyWorkbook.parse(workbookFixture(listOf("1.00"))))
        assertTrue(store.snapshot().selection.recent.isEmpty()); assertTrue(store.merchantChoices().isEmpty())
        assertEquals(DEFAULT_BOOK, store.snapshot().selection.currentBookId)
        store.saveCandidate(repeatable(), "request-1"); store.saveCandidate(repeatable(), "request-2")
        val packet = LedgerExchange.decode(store.exportExchange())
        val broken = packet.entries.map { row -> if (row.uid == "request-2") row.copy(rawSource = JSONObject(row.rawSource).put("duplicate_of_uid", "missing").toString()) else row }
        try { LedgerExchange.decode(LedgerExchange.encode(packet.copy(entries = broken))); fail() } catch (_: IllegalArgumentException) { }
        LedgerStore(context, targetName).use { target ->
            target.commitExchange(packet); assertEquals(3, target.snapshot().count)
            assertEquals(0, target.commitExchange(packet).added)
            assertTrue(target.prepareCandidate(source).repeated)
            target.saveCandidate(repeatable(), "request-3"); target.saveCandidate(repeatable(), "request-3")
            assertEquals(4, target.snapshot().count)
        }
    }
    @Test fun deletedReceiptsStillMarkRepeatButDoNotPreventAnotherExplicitSave() {
        store.saveCandidate(repeatable(), "request-1")
        val old = store.snapshot().rows.single(); store.deleteEntry(old.id, old.revision)
        assertTrue(store.prepareCandidate(source).repeated)
        store.saveCandidate(repeatable(), "request-2")
        assertEquals(1, store.snapshot().count); assertNotEquals(old.uid, store.snapshot().rows.single().uid)
    }
    @Test fun v2MigrationRetainsLedgerAndSeedsOnlyConfirmedNativeSelections() {
        store.close()
        object : SQLiteOpenHelper(context, name, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                LedgerSchema.catalog(db, legacyAccounts = true); LedgerSchema.entries(db, "entries"); LedgerSchema.receipts(db, "recognition_receipts", "entries"); LedgerSchema.imports(db)
                db.execSQL("INSERT INTO accounts VALUES('cash',?,'现金','10000',1)", arrayOf(DEFAULT_BOOK))
                db.execSQL("""INSERT INTO entries VALUES(7,'native-uid','EXPENSE',100,'合成旧商户',123,'原备注',100,100,?,NULL,'cash',NULL,0,0,1,'MANUAL','{}',0,0)""", arrayOf(DEFAULT_BOOK))
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.use { it.writableDatabase }
        store = LedgerStore(context, name)
        val snapshot = store.snapshot()
        assertEquals(9, store.readableDatabase.version); assertEquals(7L, snapshot.rows.single().id)
        assertEquals("原备注", snapshot.rows.single().note); assertEquals(BigInteger.valueOf(9900), snapshot.catalog.accounts.single().balanceMinor)
        assertEquals("cash", store.prepareCandidate(source).draft.accountId)
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
    }
    @Test fun failedV2PreferenceMigrationLeavesItsVersionAndFinancialRowsIntact() {
        store.close()
        object : SQLiteOpenHelper(context, name, null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                LedgerSchema.catalog(db, legacyAccounts = true); LedgerSchema.entries(db, "entries"); LedgerSchema.receipts(db, "recognition_receipts", "entries"); LedgerSchema.imports(db)
                db.execSQL("INSERT INTO entries VALUES(7,'native-uid','EXPENSE',101,'合成商户',123,'保留备注',100,100,?,NULL,NULL,NULL,0,0,0,'MANUAL','{}',0,0)", arrayOf(DEFAULT_BOOK))
                db.execSQL("CREATE TABLE merchant_choices(unexpected INTEGER)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.use { it.writableDatabase }
        store = LedgerStore(context, name)
        try { store.snapshot(); fail() } catch (_: android.database.SQLException) { }
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT amount_minor,note FROM entries WHERE id=7", null).use { c -> c.moveToFirst(); assertEquals(101L, c.getLong(0)); assertEquals("保留备注", c.getString(1)) }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name IN ('selection_state','selection_recent')", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
