package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.*
import io.github.localledger.core.migration.*
import org.json.JSONObject
import java.math.BigInteger
import java.util.UUID

data class ImportPlan(val total: Int, val added: Int, val skipped: Int, val conflicts: List<ImportProblem>,
                      val problems: List<ImportProblem>, val expense: BigInteger, val income: BigInteger,
                      val transfers: Int, val books: Int)
data class ImportResult(val added: Int, val skipped: Int)

private fun ImportRow.key() = "$fingerprint:$occurrence"
private fun ImportRow.type() = when (kind) { 1 -> TransactionType.EXPENSE; 2 -> TransactionType.INCOME; else -> TransactionType.TRANSFER }

internal fun LedgerStore.importPlan(db: SQLiteDatabase, document: ImportDocument): ImportPlan {
    val prior = mutableMapOf<String, MutableSet<String>>()
    val keys = mutableSetOf<String>()
    db.rawQuery("SELECT source_key,identity_key,fingerprint FROM import_receipts", null).use {
        while (it.moveToNext()) { keys += it.getString(0); prior.getOrPut(it.getString(1)) { mutableSetOf() }.add(it.getString(2)) }
    }
    val conflicts = mutableListOf<ImportProblem>()
    val problems = document.problems.toMutableList()
    var skipped = 0
    document.rows.forEach { row ->
        if (row.key() in keys) skipped++
        else if (prior[row.identity]?.let { row.fingerprint !in it } == true)
            conflicts += ImportProblem(row.sheet, row.sourceRow, "相同来源位置已有不同内容")
        try {
            require(row.book.isNotBlank() && row.book.length <= 100) { "账本名称无效" }
            require(row.remark.length <= 10000) { "备注超过 10000 字" }
            require(listOf(row.category, row.account, row.fromAccount, row.toAccount).all { it.length <= 100 }) { "分类或账户名称超过 100 字" }
            EntryDraft(row.type(), Money(row.amount), "", row.occurred, row.remark,
                categoryId = null, accountId = if (row.kind == 3) row.fromAccount.takeIf(String::isNotBlank) else null,
                toAccountId = if (row.kind == 3) row.toAccount.takeIf(String::isNotBlank) else null,
                fee = Money(row.fee), discount = Money(row.discount), affectsBalance = false)
        } catch (e: IllegalArgumentException) { problems += ImportProblem(row.sheet, row.sourceRow, e.message ?: "行无效") }
    }
    fun total(kind: Int) = document.rows.filter { it.kind == kind }.fold(BigInteger.ZERO) { s, r -> s + BigInteger.valueOf(r.amount) }
    return ImportPlan(document.rows.size, document.rows.size - skipped, skipped, conflicts, problems,
        total(1), total(2), document.rows.count { it.kind == 3 }, document.rows.map { it.book }.distinct().size)
}
fun LedgerStore.previewImport(document: ImportDocument): ImportPlan = transaction { importPlan(it, document) }

fun LedgerStore.commitImport(document: ImportDocument, affectBalances: Boolean = false, keepConflicts: Boolean = false): ImportResult = transaction { db ->
    val plan = importPlan(db, document)
    require(plan.problems.isEmpty()) { "存在无效行，请先修正文件" }
    require(keepConflicts || plan.conflicts.isEmpty()) { "存在冲突行，请选择是否保留两份" }
    var added = 0
    document.rows.forEach { row ->
        val exists = db.rawQuery("SELECT 1 FROM import_receipts WHERE source_key=?", arrayOf(row.key())).use { it.moveToFirst() }
        if (!exists) {
            fun named(table: String, name: String, type: TransactionType? = null): String {
                val where = "name=?" + (if (table == "accounts") " AND archived=0" else "") + (if (type != null) " AND type=?" else "")
                val args = listOfNotNull(name, type?.name).toTypedArray()
                db.rawQuery("SELECT id FROM $table WHERE $where", args).use { if (it.moveToFirst()) return it.getString(0) }
                if(table=="categories") CategoryPresets.known.firstOrNull { it.type==type && io.github.localledger.app.CatalogLabels.originalName(it,name) }?.let { preset ->
                    db.rawQuery("SELECT id FROM categories WHERE preset_key=? AND type=?",arrayOf(preset.key,type!!.name)).use { if(it.moveToFirst())return it.getString(0) }
                }
                val id = UUID.randomUUID().toString()
                db.insertOrThrow(table, null, ContentValues().apply {
                    put("id", id); put("name", name)
                    if (type != null) put("type", type.name)
                    if (table == "categories") { put("icon", CategoryPresets.symbol(name)); put("position", CategoryStyle.next(db, type!!)) }
                    if (table == "accounts") { put("opening_minor", "0"); put("balance_known", 0) }
                })
                return id
            }
            val book = named("books", row.book)
            val type = row.type()
            val category = row.category.takeIf(String::isNotBlank)?.let { named("categories", it, type) }
            val account = (if (row.kind == 3) row.fromAccount else row.account).takeIf(String::isNotBlank)?.let { named("accounts", it) }
            val to = row.toAccount.takeIf(String::isNotBlank)?.let { named("accounts", it) }
            val draft = EntryDraft(type, Money(row.amount), "", row.occurred, row.remark, book, category,
                account, to, Money(row.fee), Money(row.discount), affectBalances)
            val id = insertDraft(db, draft, source = "IMPORT", raw = JSONObject(row.raw).toString(), createdAtMs = row.recorded)
            db.insertOrThrow("import_receipts", null, ContentValues().apply {
                put("source_key", row.key()); put("fingerprint", row.fingerprint); put("identity_key", row.identity)
                put("file_hash", document.fileHash); put("entry_id", id); put("sheet", row.sheet); put("source_row", row.sourceRow)
            })
            added++
        }
    }
    ImportResult(added, document.rows.size - added)
}
