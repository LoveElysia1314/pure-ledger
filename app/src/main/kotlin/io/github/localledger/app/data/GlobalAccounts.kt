package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.LedgerAccount
import io.github.localledger.core.LedgerBook
import java.math.BigInteger

/** Accounts are global. Old book affiliation is used only to disambiguate legacy names. */
internal object GlobalAccounts {
    private const val INITIAL_CASH = "account-initial-cash"
    fun pristine(accounts: List<LedgerAccount>) = accounts.isEmpty() || accounts.singleOrNull()?.let {
        it.id == INITIAL_CASH && !it.archived && !it.balanceKnown && it.openingMinor == BigInteger.ZERO &&
            listOf(java.util.Locale.SIMPLIFIED_CHINESE,java.util.Locale.TRADITIONAL_CHINESE,java.util.Locale.ENGLISH,java.util.Locale.JAPANESE).any { locale ->
                it.name==io.github.localledger.app.AppStrings.inLocale(locale,io.github.localledger.app.R.string.ui_cash)
            }
    } == true
    fun ensureInitial(db: SQLiteDatabase) {
        if (db.rawQuery("SELECT 1 FROM accounts LIMIT 1", null).use { it.moveToFirst() }) return
        db.insertOrThrow("accounts", null, ContentValues().apply {
            put("id", INITIAL_CASH); put("name", io.github.localledger.app.appString(io.github.localledger.app.R.string.ui_cash))
            put("opening_minor", "0"); put("balance_known", 0)
        })
    }
    fun initialToConfigure(db: SQLiteDatabase, name: String): String? = db.rawQuery("""SELECT id FROM accounts WHERE id=? AND name=?
        AND balance_known=0 AND archived=0 AND NOT EXISTS(SELECT 1 FROM entries WHERE account_id=accounts.id OR to_account_id=accounts.id)""",
        arrayOf(INITIAL_CASH,name)).use { if(it.moveToFirst())it.getString(0) else null }

    fun create(db: SQLiteDatabase, table: String = "accounts") {
        check(table in setOf("accounts", "accounts_v8"))
        db.execSQL("""CREATE TABLE $table(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,
            opening_minor TEXT NOT NULL,balance_known INTEGER NOT NULL CHECK(balance_known IN (0,1)),archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)))""")
        db.execSQL("CREATE UNIQUE INDEX accounts_active_name ON $table(name) WHERE archived=0")
    }

    fun legacyNames(accounts: List<Pair<LedgerAccount, String>>, books: List<LedgerBook>): List<LedgerAccount> {
        val duplicates = accounts.groupingBy { it.first.name }.eachCount()
        val used = accounts.map { it.first.name }.toMutableSet()
        val names = mutableMapOf<String, String>()
        val nextSuffix = mutableMapOf<String, Int>()
        accounts.sortedBy { it.first.id }.forEach { (account, book) ->
            if (duplicates.getValue(account.name) == 1) names[account.id] = account.name
            else {
                val base = account.name.take(78) + " · " + (books.find { it.id == book }?.name ?: book).take(16)
                var name = base
                var suffix = nextSuffix[base] ?: 2
                while (!used.add(name)) {
                    val ending = " ($suffix)"
                    name = base.take(100 - ending.length) + ending
                    suffix++
                }
                nextSuffix[base] = suffix
                names[account.id] = name
            }
        }
        return accounts.map { it.first.copy(name = names.getValue(it.first.id)) }
    }

    fun upgrade(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(accounts)", null).use { c -> buildSet {
            while (c.moveToNext()) add(c.getString(1))
        } }
        if ("archived" in columns) return
        val legacy = "book_id" in columns
        val books = db.rawQuery("SELECT id,name FROM books", null).use { c -> buildList {
            while (c.moveToNext()) add(LedgerBook(c.getString(0), c.getString(1)))
        } }
        val accounts = db.rawQuery("SELECT id,name,opening_minor,balance_known" + (if (legacy) ",book_id" else "") + " FROM accounts ORDER BY id", null).use { c -> buildList {
            while (c.moveToNext()) add(LedgerAccount(c.getString(0), c.getString(1), BigInteger(c.getString(2)), c.getInt(3) == 1) to if (legacy) c.getString(4) else "")
        } }
        create(db, "accounts_v8")
        (if (legacy) legacyNames(accounts, books) else accounts.map { it.first }).forEach { account ->
            db.insertOrThrow("accounts_v8", null, ContentValues().apply {
                put("id", account.id); put("name", account.name)
                put("opening_minor", account.openingMinor.toString()); put("balance_known", if (account.balanceKnown) 1 else 0)
            })
        }
        // onConfigure disabled foreign keys before SQLiteOpenHelper began this upgrade transaction.
        // IDs remain unchanged, so every entry and saved choice continues to reference the same account.
        db.execSQL("DROP TABLE accounts")
        db.execSQL("ALTER TABLE accounts_v8 RENAME TO accounts")
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "账户迁移引用校验失败" } }
    }
}
