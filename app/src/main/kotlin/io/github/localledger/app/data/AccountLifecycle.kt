package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException

/** Removing a directory entry retains its historical identity and all financial rows. */
internal fun archiveAccount(db: SQLiteDatabase, id: String) {
    require(db.update("accounts", ContentValues().apply { put("archived", 1) }, "id=?", arrayOf(id)) == 1) { "账户不存在" }
    listOf("selection_recent", "merchant_choices").forEach { table ->
        listOf("account_id", "to_account_id").forEach { field ->
            db.update(table, ContentValues().apply { putNull(field) }, "$field=?", arrayOf(id))
        }
    }
}

fun LedgerStore.restoreAccount(id: String) = transaction { db ->
    try {
        require(db.update("accounts", ContentValues().apply { put("archived", 0) }, "id=?", arrayOf(id)) == 1) { "账户不存在" }
    } catch (_: SQLiteConstraintException) { throw IllegalArgumentException("已有同名账户，请先修改名称再恢复") }
}
