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
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerFeaturesTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-features.db"
    @Before fun prepare() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name) }
    private fun money(value: Long) = BigInteger.valueOf(value)
    private fun balance(id: String) = store.snapshot().catalog.accounts.first { it.id == id }.balanceMinor
    private fun expense(amount: Long, account: String? = null, book: String = DEFAULT_BOOK) = EntryDraft(
        TransactionType.EXPENSE, Money(amount), "样例商户", 1793419200000, accountId = account, bookId = book)

    @Test fun createEditDeleteTransferRecalculatesBothAccountsExactly() {
        val a = store.saveAccount("转出", money(100000))
        val b = store.saveAccount("转入", money(20000))
        val draft = EntryDraft(TransactionType.TRANSFER, Money(10000), "", 1, accountId = a, toAccountId = b, fee = Money(200), discount = Money(50))
        val id = store.createEntry(draft)
        assertEquals(money(90000), balance(a)); assertEquals(money(29850), balance(b))
        assertEquals(money(150), store.snapshot().transferCost)
        store.updateEntry(id, 0, draft.copy(amount = Money(5000), fee = Money(100), discount = Money(0)))
        assertEquals(money(95000), balance(a)); assertEquals(money(24900), balance(b))
        store.deleteEntry(id, 1)
        assertEquals(money(100000), balance(a)); assertEquals(money(20000), balance(b))
        assertEquals(0, store.snapshot().count)
    }
    @Test fun incomeExpenseReassignmentAndStaleEditsCannotCorruptBalances() {
        val a = store.saveAccount("账户甲", money(10000))
        val b = store.saveAccount("账户乙", money(0))
        val id = store.createEntry(expense(1234, a))
        assertEquals(money(8766), balance(a))
        store.updateEntry(id, 0, expense(1234, b).copy(type = TransactionType.INCOME))
        assertEquals(money(10000), balance(a)); assertEquals(money(1234), balance(b))
        try { store.updateEntry(id, 0, expense(999, a)); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(money(1234), balance(b)); assertEquals(money(10000), balance(a))
    }
    @Test fun manualRequestIdIsIdempotentAndConflictingRetryDoesNotOverwrite() {
        val id = store.createEntry(expense(100), "manual-request")
        assertEquals(id, store.createEntry(expense(100), "manual-request"))
        try { store.createEntry(expense(101), "manual-request"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.snapshot().count); assertEquals(100L, store.entry(id)!!.amount.minor)
    }
    @Test fun globalAccountsWorkAcrossBooksButMissingAccountsAndWrongCategoriesCannotWrite() {
        val other = store.saveBook("其他账本")
        val account = store.saveAccount("共用账户", money(0))
        store.createEntry(expense(100, account))
        store.createEntry(expense(100, account).copy(bookId = other))
        val category = store.saveCategory( TransactionType.INCOME, "收入分类")
        listOf(expense(100, "missing-account"), expense(100).copy(categoryId = category)).forEach { draft ->
            try { store.createEntry(draft); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, store.snapshot().count)
    }

    @Test fun unknownBalanceAndCalibrationKeepRecordedEffectsWithoutDoubleCounting() {
        val account = store.saveAccount("历史账户", null)
        store.createEntry(expense(500, account).copy(affectsBalance = false))
        store.createEntry(expense(100, account))
        assertFalse(store.snapshot().catalog.accounts.single().balanceKnown)
        store.saveAccount("历史账户", money(10000), account)
        assertEquals(money(10000), balance(account))
        store.createEntry(expense(123, account)); assertEquals(money(9877), balance(account))
        // Calibration may add a carry digit to opening balance; exported data must remain readable.
        val largeCurrent = BigInteger("9".repeat(80))
        store.saveAccount("历史账户", largeCurrent, account)
        val packet = LedgerExchange.decode(store.exportExchange())
        assertTrue(packet.accounts.single().openingMinor.toString().length > 80)
        assertEquals(largeCurrent, balance(account))
        assertEquals(store.snapshot().catalog.accounts.single().openingMinor, packet.accounts.single().openingMinor)
    }
    @Test fun searchRunsAcrossTheWholeLedgerAndDateRangeIsHalfOpen() {
        repeat(70) { index -> store.createEntry(expense(100).copy(merchant = if (index == 0) "唯一目标" else "其他商户", occurredAtMs = index.toLong())) }
        assertEquals(50, store.snapshot().rows.size)
        val result = store.snapshot(query = LedgerQuery(text = "唯一目标"))
        assertEquals(1, result.count); assertEquals("唯一目标", result.rows.single().merchant)
        assertEquals(10, store.snapshot(query = LedgerQuery(fromMs = 20, untilMs = 30)).count)
    }
    @Test fun monthlyCategoryTotalsStayExactAndIndependentFromAccountBalancePolicy() {
        val category = store.saveCategory( TransactionType.EXPENSE, "餐饮")
        val zone = ZoneId.systemDefault()
        val jan = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val feb = LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        store.createEntry(expense(101).copy(categoryId = category, occurredAtMs = jan, affectsBalance = false))
        store.createEntry(expense(202).copy(categoryId = category, occurredAtMs = feb))
        val selected = store.snapshot(query = LedgerQuery(categoryId = category, fromMs = jan, untilMs = feb))
        assertEquals(money(101), selected.expense); assertEquals("餐饮", selected.categories.single().name)
        assertEquals("2026-01", selected.months.single().name)
    }
    @Test fun accountDeletionRetainsHistoryAndRemovesBothUsedAndUnusedFromNewChoices() {
        val account = store.saveAccount("在用", money(0))
        val id = store.createEntry(expense(100, account)); store.deleteEntry(id, 0)
        store.deleteCatalog("accounts", account)
        assertTrue(store.snapshot().catalog.activeAccounts.isEmpty())
        assertTrue(store.snapshot().catalog.accounts.single().archived)
        val unused = store.saveAccount("未使用", null); store.deleteCatalog("accounts", unused)
        assertTrue(store.snapshot().catalog.activeAccounts.isEmpty())
        assertEquals(2, store.snapshot().catalog.accounts.size)
        assertEquals(account, LedgerExchange.decode(store.exportExchange()).entries.single().accountId)
    }
    private fun oldDatabase(brokenReceipt: Boolean = false) {
        store.close()
        object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY,type TEXT NOT NULL,amount_minor INTEGER NOT NULL,merchant TEXT NOT NULL,occurred_at INTEGER NOT NULL,note TEXT NOT NULL,created_at INTEGER NOT NULL,account_id INTEGER CHECK(account_id IS NULL))")
                db.execSQL("CREATE TABLE recognition_receipts(source_key TEXT PRIMARY KEY NOT NULL,evidence_hash TEXT NOT NULL,entry_id INTEGER NOT NULL UNIQUE REFERENCES entries(id),app_package TEXT NOT NULL,external_id TEXT NOT NULL,rule_version TEXT NOT NULL,source_amount_minor INTEGER NOT NULL,source_merchant TEXT NOT NULL,source_type TEXT NOT NULL,source_time INTEGER NOT NULL)")
                db.execSQL("INSERT INTO entries VALUES(7,'EXPENSE',9007199254740991,'旧样例',123,'原备注',100,NULL)")
                val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(9007199254740991), "旧样例", "synthetic-old", null, "v1", 123)
                db.execSQL("INSERT INTO recognition_receipts VALUES('com.tencent.mm:synthetic-old',?,?,'com.tencent.mm','synthetic-old','v1',9007199254740991,'旧样例','EXPENSE',123)", arrayOf<Any>(source.evidenceHash(), if (brokenReceipt) 999 else 7))
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.use { it.writableDatabase }
        store = LedgerStore(context, name)
    }
    @Test fun v1MigrationRetainsExactValuesIdsAndSourceRelationships() {
        oldDatabase()
        val row = store.snapshot().rows.single()
        assertEquals(7L, row.id); assertEquals(9007199254740991L, row.amount.minor)
        assertEquals("原备注", row.note); assertEquals(DEFAULT_BOOK, row.bookId); assertNull(row.accountId)
        assertEquals(9, store.readableDatabase.version)
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        assertEquals(row.uid, LedgerExchange.decode(store.exportExchange()).recognition.single().entryUid)
        store.close(); store = LedgerStore(context, name); assertEquals(row.uid, store.snapshot().rows.single().uid)
    }
    @Test fun migrationFailureRollsBackSchemaAndLeavesOriginalRows() {
        oldDatabase(true)
        try { store.snapshot(); fail() } catch (_: android.database.SQLException) { }
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT amount_minor,note FROM entries WHERE id=7", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(9007199254740991L, c.getLong(0)); assertEquals("原备注", c.getString(1))
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='books'", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
