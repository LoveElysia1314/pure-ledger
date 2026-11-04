package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.*
import java.math.BigInteger
import java.security.MessageDigest

data class BookMergePlan(val sourceId: String, val targetId: String, val sourceName: String, val targetName: String,
                         val count: Int, val targetCount: Int, val expense: BigInteger, val income: BigInteger,
                         val transfers: Int, val stateHash: String)

private fun mergePlan(db: SQLiteDatabase, source: String, target: String): BookMergePlan {
    require(source != target) { "请选择其他账本" }
    fun name(id: String) = db.rawQuery("SELECT name FROM books WHERE id=?", arrayOf(id)).use {
        require(it.moveToFirst()) { "账本不存在，请重新选择" }; it.getString(0)
    }
    val sourceName = name(source); val targetName = name(target)
    val digest = MessageDigest.getInstance("SHA-256")
    fun hash(value: String?) { val text = value.orEmpty(); digest.update("${text.length}:$text".toByteArray(Charsets.UTF_8)) }
    listOf(source, target, sourceName, targetName).forEach(::hash)
    var count = 0; var targetCount = 0; var transfers = 0
    var expense = BigInteger.ZERO; var income = BigInteger.ZERO
    db.rawQuery("SELECT id,uid,revision,deleted,book_id,type,amount_minor,category_id FROM entries WHERE book_id IN (?,?) ORDER BY id", arrayOf(source, target)).use { c ->
        while (c.moveToNext()) {
            for (i in 0 until c.columnCount) hash(c.getString(i))
            if (c.getInt(3) == 0) {
                if (c.getString(4) == target) targetCount++
                else {
                    count++
                    when (c.getString(5)) {
                        "EXPENSE" -> expense += BigInteger(c.getString(6))
                        "INCOME" -> income += BigInteger(c.getString(6))
                        "TRANSFER" -> transfers++
                    }
                }
            }
        }
    }
    return BookMergePlan(source, target, sourceName, targetName, count, targetCount, expense, income, transfers,
        digest.digest().joinToString("") { "%02x".format(it) })
}

fun LedgerStore.previewBookMerge(source: String, target: String): BookMergePlan = transaction { mergePlan(it, source, target) }

/** Shared by explicit merging and retirement of empty books that only have tombstones. */
internal fun mergeBookContents(db: SQLiteDatabase, source: String, target: String) {
    require(source != target && db.hasId("books", source) && db.hasId("books", target)) { "账本不存在" }
    val exhausted = db.rawQuery("SELECT 1 FROM entries WHERE book_id=? AND revision=? LIMIT 1", arrayOf(source, Long.MAX_VALUE.toString())).use { it.moveToFirst() }
    require(!exhausted) { "账单版本超出范围，合并未执行" }
    // Both live rows and deleted markers keep their IDs, original source and receipts.
    db.execSQL("UPDATE entries SET book_id=?,revision=revision+1,updated_at=? WHERE book_id=?", arrayOf(target, System.currentTimeMillis(), source))
    val recent = db.rawQuery("SELECT type,category_id,account_id,to_account_id,affects_balance,used_order FROM selection_recent WHERE book_id=?", arrayOf(source)).use { c -> buildList {
        while (c.moveToNext()) add(ContentValues().apply {
            put("book_id", target); put("type", c.getString(0)); put("category_id", c.getString(1))
            put("account_id", c.getString(2)); put("to_account_id", c.getString(3)); put("affects_balance", c.getInt(4)); put("used_order", c.getLong(5))
        })
    } }
    recent.forEach { value ->
        val prior = db.rawQuery("SELECT used_order FROM selection_recent WHERE book_id=? AND type=?", arrayOf(target, value.getAsString("type"))).use {
            if (it.moveToFirst()) it.getLong(0) else Long.MIN_VALUE
        }
        if (value.getAsLong("used_order") > prior) db.insertWithOnConflict("selection_recent", null, value, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    db.update("merchant_choices", ContentValues().apply { put("book_id", target) }, "book_id=?", arrayOf(source))
    listOf("book_id", "default_book_id").forEach { field ->
        db.update("selection_state", ContentValues().apply { put(field, target) }, "$field=?", arrayOf(source))
    }
    db.delete("selection_recent", "book_id=?", arrayOf(source))
    check(db.delete("books", "id=?", arrayOf(source)) == 1)
    db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "账本合并引用校验失败" } }
}

fun LedgerStore.mergeBooks(plan: BookMergePlan): String = transaction { db ->
    require(mergePlan(db, plan.sourceId, plan.targetId) == plan) { "账本内容已变化，请重新查看合并预览" }
    mergeBookContents(db, plan.sourceId, plan.targetId)
    plan.targetId
}

internal fun LedgerStore.deleteEmptyBook(db: SQLiteDatabase, id: String) {
    require(db.hasId("books", id)) { "账本不存在" }
    require(!db.rawQuery("SELECT 1 FROM entries WHERE book_id=? AND deleted=0 LIMIT 1", arrayOf(id)).use { it.moveToFirst() }) {
        "账本中仍有账单，请先合并到其他账本"
    }
    val wasDefault = selectionMemory(db).defaultBookId == id
    var remaining = catalog(db, emptyList()).books.filterNot { it.id == id }
    val replacement = if (remaining.isEmpty()) java.util.UUID.randomUUID().toString() else null
    if (replacement != null) {
        db.insertOrThrow("books", null, ContentValues().apply { put("id", replacement); put("name", "新账本-$replacement") })
        remaining = listOf(LedgerBook(replacement, io.github.localledger.app.appString(io.github.localledger.app.R.string.ui_default_ledger)))
    }
    val target = SelectionPolicy.resolve(LedgerCatalog(remaining), selectionMemory(db), TransactionType.EXPENSE).bookId
    val tombstones = db.rawQuery("SELECT 1 FROM entries WHERE book_id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
    if (tombstones) mergeBookContents(db, id, target)
    else {
        require(db.delete("books", "id=?", arrayOf(id)) == 1) { "账本不存在" }
    }
    if (wasDefault) db.update("selection_state", ContentValues().apply { putNull("default_book_id") }, "id=1", null)
    if (replacement != null) db.update("books", ContentValues().apply { put("name", io.github.localledger.app.appString(io.github.localledger.app.R.string.ui_default_ledger)) }, "id=?", arrayOf(replacement))
    if (selectionMemory(db).currentBookId == null) db.update("selection_state", ContentValues().apply { put("book_id", target) }, "id=1", null)
}
