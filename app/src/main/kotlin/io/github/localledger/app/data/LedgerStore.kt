package io.github.localledger.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.localledger.core.*
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** IO-only. Every financial mutation and its source receipt share the same SQLite transaction. */
class LedgerStore(context: Context, name: String = "ledger.db") : SQLiteOpenHelper(context, name, null, 9) {
    override fun onConfigure(db: SQLiteDatabase) {
        // Table replacement is performed in the helper's upgrade transaction and checked before commit.
        db.setForeignKeyConstraintsEnabled(db.version !in 2..8)
    }
    override fun onOpen(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) = LedgerSchema.create(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..8 && newVersion == 9) { "缺少数据库迁移" }
        if (oldVersion == 1) LedgerSchema.upgradeV1(db)
        if (oldVersion < 3) SelectionSchema.create(db)
        GlobalAccounts.upgrade(db)
        if (oldVersion < 3) seedSelectionMemory(db)
        SelectionSchema.upgradeV6(db)
        CategoryStyle.upgrade(db)
        CategoryPack.upgradeOrigins(db)
        val seeded=CategoryPack.legacySeeded(catalog(db,emptyList()))
        GlobalCategories.upgrade(db)
        if(!db.rawQuery("SELECT 1 FROM categories LIMIT 1",null).use { it.moveToFirst() })CategoryPack.ensure(db)
        else if(seeded)CategoryPack.introduceNewIncome(db)
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "数据库迁移引用校验失败" } }
    }
    @Synchronized
    internal fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    fun initializeAccountCatalog() = transaction { GlobalAccounts.ensureInitial(it) }
    @Synchronized
    fun existing(candidate: TransactionCandidate): SaveResult? {
        val key = candidate.reliableKey ?: return readableDatabase.rawQuery(
            "SELECT 1 FROM entries WHERE source='RECOGNIZED' AND raw_source LIKE ? LIMIT 1",
            arrayOf("%\"evidence_hash\":\"${candidate.evidenceHash()}\"%")
        ).use { if (it.moveToFirst()) SaveResult.AlreadySaved else null }
        readableDatabase.rawQuery("SELECT evidence_hash FROM recognition_receipts WHERE source_key=?", arrayOf(key)).use {
            return if (!it.moveToFirst()) null else if (it.getString(0) == candidate.evidenceHash()) SaveResult.AlreadySaved else SaveResult.Conflict
        }
    }
    @Synchronized
    fun save(entry: ConfirmedEntry): SaveResult = transaction { db ->
        if (entry.source.reliableKey == null) return@transaction saveCandidate(entry, UUID.randomUUID().toString())
        existing(entry.source)?.let { return@transaction it }
        val id = insertDraft(db, entry.draft(), source = "RECOGNIZED")
        val source = entry.source
        db.insertOrThrow("recognition_receipts", null, ContentValues().apply {
            put("source_key", source.reliableKey!!); put("evidence_hash", source.evidenceHash()); put("entry_id", id)
            put("app_package", source.appPackage); put("external_id", source.externalId!!); put("rule_version", source.ruleVersion)
            put("source_amount_minor", source.amount.minor); put("source_merchant", source.merchant)
            put("source_type", source.type.name); put("source_time", source.occurredAtMs!!)
        })
        rememberChoice(db, entry.draft(), source, currentBook = true)
        SaveResult.Saved(id)
    }
    @Synchronized
    fun createEntry(draft: EntryDraft, uid: String = UUID.randomUUID().toString()): Long = transaction { db ->
        entryByUid(db, uid)?.let {
            require(!it.deleted && it.draft() == draft) { "该记录已保存，请在账单中修改" }
            return@transaction it.id
        }
        val id = insertDraft(db, draft, uid = uid)
        rememberChoice(db, draft, currentBook = true)
        id
    }

    internal fun validateReferences(db: SQLiteDatabase, draft: EntryDraft, allowedArchived: Set<String> = emptySet()) {
        require(db.hasId("books", draft.bookId)) { "账本不存在" }
        draft.categoryId?.let { id ->
            db.rawQuery("SELECT type FROM categories WHERE id=?", arrayOf(id)).use {
                require(it.moveToFirst() && it.getString(0) == draft.type.name) { "分类与收支类型不符" }
            }
        }
        listOfNotNull(draft.accountId, draft.toAccountId).forEach { id ->
            db.rawQuery("SELECT archived FROM accounts WHERE id=?", arrayOf(id)).use {
                require(it.moveToFirst()) { "账户不存在" }
                require(it.getInt(0) == 0 || id in allowedArchived) { "账户已删除，请重新选择" }
            }
        }
    }
    internal fun values(draft: EntryDraft) = ContentValues().apply {
        put("type", draft.type.name); put("amount_minor", draft.amount.minor); put("merchant", draft.merchant)
        put("occurred_at", draft.occurredAtMs); put("note", draft.note); put("book_id", draft.bookId)
        put("category_id", draft.categoryId); put("account_id", draft.accountId); put("to_account_id", draft.toAccountId)
        put("fee_minor", draft.fee.minor); put("discount_minor", draft.discount.minor)
        put("affects_balance", if (draft.affectsBalance) 1 else 0); put("updated_at", System.currentTimeMillis())
    }
    internal fun insertDraft(db: SQLiteDatabase, draft: EntryDraft, uid: String = UUID.randomUUID().toString(),
                             source: String = "MANUAL", raw: String = "{}", revision: Long = 0, deleted: Boolean = false,
                             createdAtMs: Long = System.currentTimeMillis(), updatedAtMs: Long = createdAtMs, allowArchived: Boolean = false): Long {
        validateReferences(db, draft, if (allowArchived) listOfNotNull(draft.accountId, draft.toAccountId).toSet() else emptySet())
        val values = values(draft).apply {
            put("uid", uid); put("created_at", createdAtMs); put("updated_at", updatedAtMs); put("source", source); put("raw_source", raw)
            put("revision", revision); put("deleted", if (deleted) 1 else 0)
        }
        return db.insertOrThrow("entries", null, values)
    }
    @Synchronized
    fun updateEntry(id: Long, expectedRevision: Long, draft: EntryDraft) = transaction { db ->
        val original = db.rawQuery("SELECT account_id,to_account_id FROM entries WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) listOfNotNull(it.getString(0), it.getString(1)).toSet() else emptySet()
        }
        validateReferences(db, draft, original)
        val values = values(draft).apply { put("revision", expectedRevision + 1) }
        require(db.update("entries", values, "id=? AND revision=? AND deleted=0", arrayOf(id.toString(), expectedRevision.toString())) == 1) {
            "账单已变化，请重新打开"
        }
        rememberChoice(db, draft)
    }
    @Synchronized
    fun deleteEntry(id: Long, expectedRevision: Long) = transaction { db ->
        val values = ContentValues().apply { put("deleted", 1); put("revision", expectedRevision + 1); put("updated_at", System.currentTimeMillis()) }
        require(db.update("entries", values, "id=? AND revision=? AND deleted=0", arrayOf(id.toString(), expectedRevision.toString())) == 1) {
            "账单已变化，请重新打开"
        }
    }
    @Synchronized
    fun entry(id: Long): LedgerRow? = readableDatabase.rawQuery("SELECT * FROM entries WHERE id=? AND deleted=0", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) row(it) else null }

    internal fun entryByUid(db: SQLiteDatabase, uid: String): LedgerRow? =
        db.rawQuery("SELECT * FROM entries WHERE uid=?", arrayOf(uid)).use { if (it.moveToFirst()) row(it) else null }

    internal fun allRows(db: SQLiteDatabase, includeDeleted: Boolean = false, includeSource: Boolean = true): List<LedgerRow> {
        // Financial summaries need every applicable amount, but not large original exports.
        // Full sources are fetched below for the visible page, and remain full for exchange/migrations.
        val projection = if (includeSource) "*" else """id,uid,type,amount_minor,merchant,occurred_at,note,created_at,updated_at,
            book_id,category_id,account_id,to_account_id,fee_minor,discount_minor,affects_balance,source,
            '{}' AS raw_source,revision,deleted"""
        return db.rawQuery("SELECT $projection FROM entries ${if (includeDeleted) "" else "WHERE deleted=0"} ORDER BY occurred_at DESC,id DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }

    private fun pageWithSources(db: SQLiteDatabase, page: List<LedgerRow>): List<LedgerRow> {
        val sources = mutableMapOf<Long, String>()
        // Stay below the variable limit on the oldest supported SQLite, even after many load-more operations.
        page.chunked(900).forEach { part ->
            db.rawQuery("SELECT id,raw_source FROM entries WHERE id IN (${part.joinToString { "?" }})", part.map { it.id.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) sources[c.getLong(0)] = c.getString(1)
            }
        }
        return page.map { it.copy(rawSource = sources.getValue(it.id)) }
    }
    private fun row(c: Cursor): LedgerRow {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun number(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        return LedgerRow(number("id"), TransactionType.valueOf(text("type")), Money(number("amount_minor")),
            text("merchant"), number("occurred_at"), text("note"), text("uid"), number("revision"), text("book_id"),
            text("category_id"), text("account_id"), text("to_account_id"), Money(number("fee_minor")), Money(number("discount_minor")),
            number("affects_balance") == 1L, text("source"), text("raw_source"), number("deleted") == 1L, number("created_at"), number("updated_at"))
    }
    internal fun catalog(db: SQLiteDatabase, rows: List<LedgerRow>): LedgerCatalog {
        val books = db.rawQuery("SELECT id,name FROM books ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(LedgerBook(c.getString(0), c.getString(1))) }
        }
        val categories = db.rawQuery("SELECT id,type,name,icon,position,hidden,preset_key FROM categories ORDER BY position,name,id", null).use { c ->
            buildList { while (c.moveToNext()) add(LedgerCategory(c.getString(0), TransactionType.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5) == 1, c.getString(6))) }
        }
        val effects = mutableMapOf<String, BigInteger>()
        rows.filterNot { it.deleted }.forEach { row -> row.draft().accountEffects().forEach { (id, value) -> effects[id] = effects.getOrDefault(id, BigInteger.ZERO) + value } }
        val accounts = db.rawQuery("SELECT id,name,opening_minor,balance_known,archived FROM accounts ORDER BY name,id", null).use { c ->
            buildList { while (c.moveToNext()) {
                val opening = BigInteger(c.getString(2)); val id = c.getString(0)
                add(LedgerAccount(id, c.getString(1), opening, c.getInt(3) == 1, opening + effects.getOrDefault(id, BigInteger.ZERO), c.getInt(4) == 1))
            } }
        }
        return LedgerCatalog(books, categories.sortedWith(compareBy({ it.type.ordinal }, { it.hidden }, { it.position }, { it.name }, { it.id })), accounts)
    }
    @Synchronized
    fun snapshot(limit: Int = 50, query: LedgerQuery = LedgerQuery()): LedgerSnapshot = transaction { db ->
        require(limit > 0)
        val all = allRows(db, includeSource = false); val catalog = catalog(db, all)
        val categories = catalog.categories.associateBy { it.id }; val accounts = catalog.accounts.associateBy { it.id }
        val needle = query.text.trim()
        val rows = all.filter { r ->
            (query.bookId == null || r.bookId == query.bookId) && (query.type == null || r.type == query.type) &&
                (query.categoryId == null || r.categoryId == query.categoryId) &&
                (query.accountId == null || r.accountId == query.accountId || r.toAccountId == query.accountId) &&
                (query.fromMs?.let { r.occurredAtMs >= it } != false) && (query.untilMs?.let { r.occurredAtMs < it } != false) &&
                (query.minimumMinor?.let { r.amount.minor >= it } != false) && (query.maximumMinor?.let { r.amount.minor <= it } != false) &&
                (needle.isEmpty() || listOf(r.merchant, r.note, categories[r.categoryId]?.name.orEmpty(), io.github.localledger.app.CatalogLabels.category(catalog, r.categoryId, r.type), accounts[r.accountId]?.name.orEmpty(), accounts[r.toAccountId]?.name.orEmpty())
                    .any { it.contains(needle, ignoreCase = true) })
        }
        val cashFlow = CashFlow.summarize(rows.asSequence().map { it.draft() })
        val zone = ZoneId.systemDefault()
        fun periods(monthly: Boolean) = rows.groupBy {
            val day = Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).toLocalDate().toString()
            if (monthly) day.take(7) else day
        }.map { (key, values) -> PeriodStatement(key, CashFlow.summarize(values.asSequence().map { it.draft() })) }.sortedByDescending { it.key }
        val buckets = rows.filter { it.type != TransactionType.TRANSFER }.groupBy { it.type to io.github.localledger.app.CatalogLabels.category(catalog, it.categoryId, it.type) }
            .map { (key, values) -> SummaryBucket(key.second, key.first, values.fold(BigInteger.ZERO) { s, r -> s + BigInteger.valueOf(r.amount.minor) }, values.size) }
            .sortedByDescending { it.amount }
        val months = rows.filter { it.type != TransactionType.TRANSFER }.groupBy {
            it.type to Instant.ofEpochMilli(it.occurredAtMs).atZone(ZoneId.systemDefault()).toLocalDate().withDayOfMonth(1).toString().take(7)
        }.map { (key, values) -> SummaryBucket(key.second, key.first, values.fold(BigInteger.ZERO) { s, r -> s + BigInteger.valueOf(r.amount.minor) }, values.size) }
            .sortedByDescending { it.name }
        LedgerSnapshot(pageWithSources(db, rows.take(limit)), rows.size, cashFlow.expense, cashFlow.income, cashFlow.transferCost,
            catalog, buckets, months, query, selectionMemory(db), cashFlow, periods(false), periods(true))
    }
    @Synchronized
    fun saveBook(name: String, id: String? = null, defaultBook: Boolean? = null): String = transaction { db ->
        require(name.trim().isNotEmpty() && name.length <= 100) { "账本名称需为 1–100 字" }
        val key = id ?: UUID.randomUUID().toString()
        val values = ContentValues().apply { put("id", key); put("name", name.trim()) }
        if (id == null) db.insertOrThrow("books", null, values) else require(db.update("books", values, "id=?", arrayOf(id)) == 1) { "账本不存在" }
        if (defaultBook == true || defaultBook == false && selectionMemory(db).defaultBookId == key) {
            db.update("selection_state", ContentValues().apply { put("default_book_id", if (defaultBook == true) key else null as String?) }, "id=1", null)
        }
        key
    }
    @Synchronized
    fun saveCategory(type: TransactionType, name: String, id: String? = null, icon: String? = null, hidden: Boolean? = null, requireNew: Boolean = false): String = transaction { db ->
        require(type != TransactionType.TRANSFER && name.trim().isNotEmpty() && name.length <= 100) { "分类名称或类型无效" }
        require(icon == null || icon in CategoryIcons.keys) { "分类图标无效" }
        val oldName=id?.let { db.rawQuery("SELECT name FROM categories WHERE id=?",arrayOf(it)).use { c->if(c.moveToFirst())c.getString(0) else null } }
        if(id==null || name.trim()!=oldName)db.rawQuery("SELECT id FROM categories WHERE type=? AND name=? AND id<>?", arrayOf(type.name, name.trim(), id.orEmpty())).use {
            if (it.moveToFirst()) { require(id == null && !requireNew) { "已有同名分类，请修改名称" }; return@transaction it.getString(0) }
        }
        val key = id ?: UUID.randomUUID().toString()
        val values = ContentValues().apply { put("id", key); put("type", type.name); put("name", name.trim()) }
        if (id == null) { values.put("icon", icon ?: CategoryPresets.symbol(name.trim())); values.put("position", CategoryStyle.next(db, type, hidden == true)) }
        else if (icon != null) values.put("icon", icon)
        if (hidden != null) values.put("hidden", if (hidden) 1 else 0)
        if (id == null) db.insertOrThrow("categories", null, values) else {
            db.rawQuery("SELECT type FROM categories WHERE id=?", arrayOf(id)).use {
                require(it.moveToFirst() && it.getString(0) == type.name) { "已有分类不能更改类型" }
            }
            db.update("categories", values, "id=?", arrayOf(id))
        }
        key
    }
    @Synchronized
    fun saveAccount(name: String, opening: BigInteger?, id: String? = null): String = transaction { db ->
        require(name.trim().isNotEmpty() && name.length <= 100) { "账户名称需为 1–100 字" }
        val effectiveId=id ?: GlobalAccounts.initialToConfigure(db,name.trim())
        val key = effectiveId ?: UUID.randomUUID().toString()
        val values = ContentValues().apply { put("id", key); put("name", name.trim()) }
        if (effectiveId == null) {
            values.put("opening_minor", (opening ?: BigInteger.ZERO).toString()); values.put("balance_known", if (opening == null) 0 else 1)
            db.insertOrThrow("accounts", null, values)
        } else {
            require(db.hasId("accounts", effectiveId)) { "账户不存在" }
            // Optional value is the user's current balance; preserve existing transaction effects.
            if (opening != null) {
                val effects = allRows(db).fold(BigInteger.ZERO) { sum, r -> sum + r.draft().accountEffects().getOrDefault(effectiveId, BigInteger.ZERO) }
                values.put("opening_minor", (opening - effects).toString()); values.put("balance_known", 1)
            }
            db.update("accounts", values, "id=?", arrayOf(effectiveId))
        }
        key
    }
    @Synchronized
    fun deleteCatalog(kind: String, id: String) = transaction { db ->
        require(kind in setOf("books", "accounts", "categories"))
        try {
            if (kind == "accounts") archiveAccount(db, id)
            else if (kind == "books") deleteEmptyBook(db, id)
            else require(db.delete(kind, "id=?", arrayOf(id)) == 1) { "项目不存在" }
        }
        catch (_: android.database.sqlite.SQLiteConstraintException) { throw IllegalArgumentException("仍有账单或关联项目使用，不能删除") }
    }
}

internal fun SQLiteDatabase.hasId(table: String, id: String): Boolean =
    rawQuery("SELECT 1 FROM $table WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
