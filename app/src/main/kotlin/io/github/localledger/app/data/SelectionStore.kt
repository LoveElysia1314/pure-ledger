package io.github.localledger.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.*

internal object SelectionSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE selection_state(id INTEGER PRIMARY KEY CHECK(id=1),book_id TEXT REFERENCES books(id) ON DELETE SET NULL,default_book_id TEXT REFERENCES books(id) ON DELETE SET NULL)")
        db.execSQL("INSERT INTO selection_state(id,book_id) VALUES(1,?)", arrayOf(DEFAULT_BOOK))
        val fields = """book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            type TEXT NOT NULL CHECK(type IN ('EXPENSE','INCOME','TRANSFER')),
            category_id TEXT REFERENCES categories(id) ON DELETE SET NULL,
            account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
            to_account_id TEXT REFERENCES accounts(id) ON DELETE SET NULL,
            affects_balance INTEGER NOT NULL CHECK(affects_balance IN (0,1))"""
        db.execSQL("CREATE TABLE selection_recent($fields,used_order INTEGER NOT NULL,PRIMARY KEY(book_id,type))")
        db.execSQL("CREATE TABLE merchant_choices(app_package TEXT NOT NULL,merchant TEXT NOT NULL,$fields,PRIMARY KEY(app_package,merchant,type))")
    }
    fun upgradeV6(db: SQLiteDatabase) {
        val exists = db.rawQuery("PRAGMA table_info(selection_state)", null).use { c ->
            var found = false; while (c.moveToNext()) if (c.getString(1) == "default_book_id") found = true; found
        }
        if (!exists) db.execSQL("ALTER TABLE selection_state ADD COLUMN default_book_id TEXT REFERENCES books(id) ON DELETE SET NULL")
    }
}
private fun Cursor.choice() = EntryChoice(getString(getColumnIndexOrThrow("book_id")),
    getString(getColumnIndexOrThrow("category_id")), getString(getColumnIndexOrThrow("account_id")),
    getString(getColumnIndexOrThrow("to_account_id")), getInt(getColumnIndexOrThrow("affects_balance")) == 1)
internal fun LedgerStore.selectionMemory(db: SQLiteDatabase): SelectionMemory {
    val books = db.rawQuery("SELECT book_id,default_book_id FROM selection_state WHERE id=1", null).use {
        if (it.moveToFirst()) it.getString(0) to it.getString(1) else null to null
    }
    val recent = db.rawQuery("SELECT * FROM selection_recent ORDER BY used_order", null).use { c -> buildList {
        while (c.moveToNext()) add(RecentChoice(TransactionType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))), c.choice(), c.getLong(c.getColumnIndexOrThrow("used_order"))))
    } }
    return SelectionMemory(books.first, recent, books.second)
}
internal fun LedgerStore.seedSelectionMemory(db: SQLiteDatabase) {
    allRows(db).filter { it.source != "IMPORT" }.sortedWith(compareBy({ it.updatedAtMs }, { it.id })).forEach { row ->
        val source = db.rawQuery("SELECT * FROM recognition_receipts WHERE entry_id=?", arrayOf(row.id.toString())).use { c ->
            if (!c.moveToFirst()) null else {
                fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
                TransactionCandidate(text("app_package"), TransactionType.valueOf(text("source_type")), Money(text("source_amount_minor").toLong()),
                    text("source_merchant"), text("external_id"), null, text("rule_version"), text("source_time").toLong())
            }
        }
        val draft = row.draft().let { if (it.accountId == null) it.copy(affectsBalance = true) else it }
        rememberChoice(db, draft, source, currentBook = row.source == "MANUAL")
    }
}
fun LedgerStore.merchantChoices(): List<MerchantChoice> = transaction { db ->
    db.rawQuery("SELECT * FROM merchant_choices ORDER BY merchant,app_package,type", null).use { c -> buildList {
        while (c.moveToNext()) add(MerchantChoice(c.getString(c.getColumnIndexOrThrow("app_package")), c.getString(c.getColumnIndexOrThrow("merchant")),
            TransactionType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))), c.choice()))
    } }
}
fun LedgerStore.forgetMerchant(value: MerchantChoice) = transaction { db ->
    db.delete("merchant_choices", "app_package=? AND merchant=? AND type=?", arrayOf(value.appPackage, value.merchant, value.type.name))
}
fun LedgerStore.setCurrentBook(book: String) = transaction { db ->
    require(db.hasId("books", book)) { "账本不存在" }
    db.update("selection_state", ContentValues().apply { put("book_id", book) }, "id=1", null)
}
fun LedgerStore.setDefaultBook(book: String?) = transaction { db ->
    require(book == null || db.hasId("books", book)) { "账本不存在" }
    db.update("selection_state", ContentValues().apply { put("default_book_id", book) }, "id=1", null)
}
internal fun LedgerStore.rememberChoice(db: SQLiteDatabase, draft: EntryDraft, source: TransactionCandidate? = null, currentBook: Boolean = false) {
    val order = db.rawQuery("SELECT COALESCE(MAX(used_order),0)+1 FROM selection_recent", null).use { it.moveToFirst(); it.getLong(0) }
    fun active(id: String?) = id?.takeIf { key -> db.rawQuery("SELECT 1 FROM accounts WHERE id=? AND archived=0", arrayOf(key)).use { it.moveToFirst() } }
    val values = ContentValues().apply {
        put("book_id", draft.bookId); put("type", draft.type.name); put("category_id", draft.categoryId)
        put("account_id", active(draft.accountId)); put("to_account_id", active(draft.toAccountId)); put("affects_balance", if (draft.affectsBalance) 1 else 0)
    }
    db.insertWithOnConflict("selection_recent", null, ContentValues(values).apply { put("used_order", order) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    if (source != null) db.insertWithOnConflict("merchant_choices", null, ContentValues(values).apply {
        put("app_package", source.appPackage); put("merchant", source.merchant.trim())
    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    if (currentBook) db.update("selection_state", ContentValues().apply { put("book_id", draft.bookId) }, "id=1", null)
}
data class CandidatePreparation(val draft: EntryDraft, val repeated: Boolean)
fun LedgerStore.prepareCandidate(source: TransactionCandidate): CandidatePreparation = transaction { db ->
    // SelectionPolicy uses account identity and ownership, never balances or historical rows.
    val catalog = catalog(db, emptyList())
    val mapped = db.rawQuery("SELECT * FROM merchant_choices WHERE app_package=? AND merchant=? AND type=?",
        arrayOf(source.appPackage, source.merchant.trim(), source.type.name)).use { if (it.moveToFirst()) it.choice() else null }
    val choice = SelectionPolicy.resolve(catalog, selectionMemory(db), source.type, merchant = mapped, appPackage = source.appPackage)
    CandidatePreparation(EntryDraft(source.type, source.amount, source.merchant, source.occurredAtMs ?: 0,
        bookId = choice.bookId, categoryId = choice.categoryId, accountId = choice.accountId, toAccountId = choice.toAccountId, affectsBalance = choice.affectsBalance), existing(source) != null)
}
