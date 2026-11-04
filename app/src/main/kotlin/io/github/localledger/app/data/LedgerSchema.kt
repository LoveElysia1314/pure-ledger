package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.DEFAULT_BOOK
import java.util.UUID

internal object LedgerSchema {
    fun catalog(db: SQLiteDatabase, legacyAccounts: Boolean = false) {
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL UNIQUE CHECK(length(trim(name))>0))")
        if (legacyAccounts) db.execSQL("""CREATE TABLE accounts(id TEXT PRIMARY KEY NOT NULL,book_id TEXT NOT NULL REFERENCES books(id),
            name TEXT NOT NULL,opening_minor TEXT NOT NULL,balance_known INTEGER NOT NULL CHECK(balance_known IN (0,1)),
            UNIQUE(book_id,name))""") else GlobalAccounts.create(db)
        GlobalCategories.create(db)
        db.execSQL("INSERT INTO books(id,name) VALUES(?,?)", arrayOf(DEFAULT_BOOK, io.github.localledger.app.appString(io.github.localledger.app.R.string.ui_default_ledger)))
    }
    fun entries(db: SQLiteDatabase, name: String) {
        db.execSQL("""CREATE TABLE $name(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uid TEXT NOT NULL UNIQUE,
            type TEXT NOT NULL CHECK(type IN ('EXPENSE','INCOME','TRANSFER')),
            amount_minor INTEGER NOT NULL CHECK(amount_minor>=0),merchant TEXT NOT NULL,occurred_at INTEGER NOT NULL,
            note TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
            book_id TEXT NOT NULL REFERENCES books(id),category_id TEXT REFERENCES categories(id),
            account_id TEXT REFERENCES accounts(id),to_account_id TEXT REFERENCES accounts(id),
            fee_minor INTEGER NOT NULL CHECK(fee_minor>=0),discount_minor INTEGER NOT NULL CHECK(discount_minor>=0),
            affects_balance INTEGER NOT NULL CHECK(affects_balance IN (0,1)),
            source TEXT NOT NULL,raw_source TEXT NOT NULL,revision INTEGER NOT NULL,deleted INTEGER NOT NULL CHECK(deleted IN (0,1)))""")
    }
    fun receipts(db: SQLiteDatabase, name: String, entries: String) {
        db.execSQL("""CREATE TABLE $name(
            source_key TEXT PRIMARY KEY NOT NULL,evidence_hash TEXT NOT NULL,
            entry_id INTEGER NOT NULL UNIQUE REFERENCES $entries(id) ON DELETE RESTRICT,
            app_package TEXT NOT NULL,external_id TEXT NOT NULL,rule_version TEXT NOT NULL,
            source_amount_minor INTEGER NOT NULL,source_merchant TEXT NOT NULL,source_type TEXT NOT NULL,source_time INTEGER NOT NULL)""")
    }
    fun imports(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE import_receipts(source_key TEXT PRIMARY KEY NOT NULL,
            fingerprint TEXT NOT NULL,identity_key TEXT NOT NULL,file_hash TEXT NOT NULL,
            entry_id INTEGER NOT NULL REFERENCES entries(id),sheet TEXT NOT NULL,source_row INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX import_identity ON import_receipts(identity_key)")
        db.execSQL("CREATE INDEX entries_time ON entries(deleted,occurred_at DESC,id DESC)")
        db.execSQL("CREATE INDEX entries_book ON entries(book_id,deleted)")
    }
    fun create(db: SQLiteDatabase) {
        catalog(db); entries(db, "entries"); receipts(db, "recognition_receipts", "entries"); imports(db); SelectionSchema.create(db)
        CategoryPack.ensure(db)
    }
    fun upgradeV1(db: SQLiteDatabase) {
        catalog(db); entries(db, "entries_new"); receipts(db, "recognition_receipts_new", "entries_new")
        db.rawQuery("SELECT id,type,amount_minor,merchant,occurred_at,note,created_at FROM entries", null).use { c ->
            while (c.moveToNext()) {
                val values = ContentValues().apply {
                    put("id", c.getLong(0)); put("uid", UUID.randomUUID().toString()); put("type", c.getString(1))
                    put("amount_minor", c.getLong(2)); put("merchant", c.getString(3)); put("occurred_at", c.getLong(4))
                    put("note", c.getString(5)); put("created_at", c.getLong(6)); put("updated_at", c.getLong(6))
                    put("book_id", DEFAULT_BOOK); put("fee_minor", 0); put("discount_minor", 0)
                    put("affects_balance", 0); put("source", "RECOGNIZED"); put("raw_source", "{}")
                    put("revision", 0); put("deleted", 0)
                }
                db.insertOrThrow("entries_new", null, values)
            }
        }
        db.execSQL("INSERT INTO recognition_receipts_new SELECT * FROM recognition_receipts")
        db.execSQL("DROP TABLE recognition_receipts"); db.execSQL("DROP TABLE entries")
        db.execSQL("ALTER TABLE entries_new RENAME TO entries")
        db.execSQL("ALTER TABLE recognition_receipts_new RENAME TO recognition_receipts")
        imports(db)
    }
}
