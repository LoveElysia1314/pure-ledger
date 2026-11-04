package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SourceReceipt(val fields: Map<String, String>, val entryUid: String)
data class ExchangeData(val books: List<LedgerBook>, val accounts: List<LedgerAccount>, val categories: List<LedgerCategory>,
                        val entries: List<LedgerRow>, val recognition: List<SourceReceipt>, val imports: List<SourceReceipt>, val schemaVersion: Int = 5)
data class ExchangePlan(val added: Int, val skipped: Int, val conflicts: List<String>, val total: Int)

/** Lossless supported-data exchange, distinct from the future attachment backup format. */
object LedgerExchange {
    private const val FORMAT = "local-ledger-exchange"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun array(values: List<JSONObject>) = JSONArray().apply { values.forEach(::put) }
    private fun nullable(obj: JSONObject, key: String, value: String?) { obj.put(key, value ?: JSONObject.NULL) }
    private fun entryJson(r: LedgerRow) = JSONObject().apply {
        put("uid", r.uid); put("type", r.type.name); put("amountMinor", r.amount.minor.toString()); put("merchant", r.merchant)
        put("occurredAtMs", r.occurredAtMs.toString()); put("note", r.note); put("bookId", r.bookId)
        nullable(this, "categoryId", r.categoryId); nullable(this, "accountId", r.accountId); nullable(this, "toAccountId", r.toAccountId)
        put("feeMinor", r.fee.minor.toString()); put("discountMinor", r.discount.minor.toString()); put("affectsBalance", r.affectsBalance)
        put("source", r.source); put("rawSource", r.rawSource); put("revision", r.revision.toString()); put("deleted", r.deleted)
        put("createdAtMs", r.createdAtMs.toString()); put("updatedAtMs", r.updatedAtMs.toString())
    }
    fun encode(data: ExchangeData): ByteArray {
        require(listOf(data.books.size, data.accounts.size, data.categories.size, data.entries.size, data.recognition.size, data.imports.size).all { it <= 100_000 }) { "导出记录数量超过上限" }
        require(data.entries.sumOf { (it.merchant.length + it.note.length + it.rawSource.length).toLong() * 3 } <= 36L * 1024 * 1024) { "导出数据超过大小上限" }
        val records = JSONObject().apply {
            put("books", array(data.books.map { JSONObject().put("id", it.id).put("name", it.name) }))
            put("accounts", array(data.accounts.map { JSONObject().put("id", it.id).put("name", it.name)
                .put("openingMinor", it.openingMinor.toString()).put("balanceKnown", it.balanceKnown).put("archived", it.archived) }))
            put("categories", array(data.categories.map { JSONObject().put("id", it.id).put("name", it.name).put("type", it.type.name)
                .put("icon", it.icon).put("position", it.position.toString()).put("hidden", it.hidden).put("presetKey", it.presetKey ?: JSONObject.NULL) }))
            put("entries", array(data.entries.map(::entryJson)))
            fun receipts(list: List<SourceReceipt>) = array(list.map { JSONObject(it.fields).put("entryUid", it.entryUid) })
            put("recognition", receipts(data.recognition)); put("imports", receipts(data.imports))
        }.toString().toByteArray(Charsets.UTF_8)
        require(records.size <= 39 * 1024 * 1024) { "导出数据超过大小上限" }
        val manifest = JSONObject().put("format", FORMAT).put("schemaVersion", 5).put("currency", "CNY").put("scale", 2)
            .put("bytes", records.size).put("sha256", hash(records)).toString().toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to manifest, "records.json" to records).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        } }.toByteArray().also { require(it.size <= 20 * 1024 * 1024) { "导出文件超过 20 MB" } }
    }
    fun isExchange(bytes: ByteArray): Boolean = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var count = 0; var total = 0; val buffer = ByteArray(8192)
        while (count++ < 1000) {
            val e = zip.nextEntry ?: return@use false
            if (e.name == "manifest.json") return@use true
            while (true) { val read = zip.read(buffer); if (read < 0) break; total += read; require(total <= 40 * 1024 * 1024) { "展开后的数据超过 40 MB" } }
        }
        false
    }
    fun decode(bytes: ByteArray): ExchangeData {
        require(bytes.size in 1..20 * 1024 * 1024) { "数据文件为空或超过 20 MB" }
        val members = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in setOf("manifest.json", "records.json") && !members.containsKey(entry.name)) { "数据文件包含无效或重复成员" }
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer); if (read < 0) break
                    total += read; require(total <= 40 * 1024 * 1024) { "展开后的数据超过 40 MB" }; output.write(buffer, 0, read)
                }
                members[entry.name] = output.toByteArray()
            }
        }
        require(members.keys == setOf("manifest.json", "records.json")) { "数据文件缺少清单或记录" }
        fun json(bytes: ByteArray): JSONObject {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            StrictJson.validate(text); return JSONObject(text)
        }
        val manifest = json(members.getValue("manifest.json"))
        val version = manifest.get("schemaVersion")
        require(manifest.getString("format") == FORMAT && version in setOf(1, 2, 3, 4, 5) &&
            manifest.getString("currency") == "CNY" && manifest.get("scale") == 2) { "不支持的数据文件版本或币种" }
        val records = members.getValue("records.json")
        require(records.size == manifest.getInt("bytes") && hash(records) == manifest.getString("sha256")) { "数据文件校验失败" }
        val root = json(records)
        require(root.keys().asSequence().toSet() == setOf("books", "accounts", "categories", "entries", "recognition", "imports")) { "数据文件字段不完整或包含未知内容" }
        fun JSONObject.text(key: String): String = (get(key) as? String) ?: throw IllegalArgumentException("字段 $key 需要文字值")
        fun JSONObject.id(key: String) = text(key).also { require(it.isNotBlank() && it.length <= 200) { "标识无效" } }
        fun JSONObject.optional(key: String): String? = if (isNull(key)) null else id(key)
        fun JSONObject.integer(key: String): String = text(key).also { require(it.length <= 128 && Regex("-?(0|[1-9][0-9]*)").matches(it)) { "整数格式无效" } }
        fun JSONObject.long(key: String) = integer(key).toLong()
        fun JSONObject.name(): String = text("name").also { require(it.isNotBlank() && it.length <= 100) { "名称无效" } }
        fun JSONObject.boolean(key: String) = (get(key) as? Boolean) ?: throw IllegalArgumentException("布尔字段无效")
        fun JSONObject.fields(vararg keys: String) { require(this.keys().asSequence().toSet() == keys.toSet()) { "存在缺失或未知字段" } }
        fun <T> list(key: String, block: (JSONObject) -> T): List<T> {
            val array = root.getJSONArray(key); require(array.length() <= 100_000) { "记录数量过多" }
            return (0 until array.length()).map { block(array.getJSONObject(it)) }
        }
        val books = list("books") { it.fields("id", "name"); LedgerBook(it.id("id"), it.name()) }
        val legacyAccounts = mutableListOf<Pair<LedgerAccount, String>>()
        val decodedAccounts = list("accounts") {
            if (version in setOf(4, 5)) it.fields("id", "name", "openingMinor", "balanceKnown", "archived")
            else if (version == 3) it.fields("id", "name", "openingMinor", "balanceKnown")
            else it.fields("id", "bookId", "name", "openingMinor", "balanceKnown")
            val account = LedgerAccount(it.id("id"), it.name(), BigInteger(it.integer("openingMinor")), it.boolean("balanceKnown"), archived = if (version in setOf(4,5)) it.boolean("archived") else false)
            if (version in setOf(1, 2)) legacyAccounts += account to it.id("bookId")
            account
        }
        require(legacyAccounts.all { (_, book) -> books.any { it.id == book } }) { "账户原账本引用无效" }
        val accounts = if (version in setOf(3, 4, 5)) decodedAccounts else GlobalAccounts.legacyNames(legacyAccounts, books)
        val origins = CategoryPack.legacyOrigins(books.map { it.id })
        val legacyCategoryBooks=mutableMapOf<String,String>()
        val categories = list("categories") {
            if(version!=5)legacyCategoryBooks[it.id("id")]=it.id("bookId")
            if (version == 1) {
                it.fields("id", "bookId", "name", "type")
                LedgerCategory(it.id("id"), TransactionType.valueOf(it.text("type")), it.name())
            } else {
                if (version == 5) it.fields("id", "name", "type", "icon", "position", "hidden", "presetKey")
                else if (version == 4) it.fields("id", "bookId", "name", "type", "icon", "position", "hidden", "presetKey")
                else it.fields("id", "bookId", "name", "type", "icon", "position", "hidden")
                val icon = it.text("icon"); val position = it.long("position")
                require(icon in CategoryIcons.keys && position in 0 until Long.MAX_VALUE) { "分类图标或顺序无效" }
                LedgerCategory(it.id("id"), TransactionType.valueOf(it.text("type")), it.name(), icon, position, it.boolean("hidden"), if (version in setOf(4,5)) it.optional("presetKey") else null)
            }
        }
        val resolvedCategories = categories.map { category ->
            if (version in setOf(4,5)) category else category.copy(presetKey = origins[category.id]?.takeIf { it.type == category.type }?.key)
        }
        val presetKeys = CategoryPresets.known.associateBy { it.key }
        require(resolvedCategories.all { it.presetKey == null || presetKeys[it.presetKey]?.type == it.type }) { "分类来源无效" }
        val entries = list("entries") { o ->
            o.fields("uid", "type", "amountMinor", "merchant", "occurredAtMs", "note", "bookId", "categoryId", "accountId", "toAccountId", "feeMinor", "discountMinor", "affectsBalance", "source", "rawSource", "revision", "deleted", "createdAtMs", "updatedAtMs")
            val row = LedgerRow(0, TransactionType.valueOf(o.text("type")), Money(o.long("amountMinor")), o.text("merchant"),
                o.long("occurredAtMs"), o.text("note"), o.id("uid"), o.long("revision"), o.id("bookId"), o.optional("categoryId"),
                o.optional("accountId"), o.optional("toAccountId"), Money(o.long("feeMinor")), Money(o.long("discountMinor")),
                o.boolean("affectsBalance"), o.text("source"), o.text("rawSource"), o.boolean("deleted"), o.long("createdAtMs"), o.long("updatedAtMs"))
            require(row.revision >= 0 && row.source in setOf("MANUAL", "RECOGNIZED", "IMPORT")) { "记录来源或版本无效" }
            StrictJson.validate(row.rawSource); JSONObject(row.rawSource); row.draft(); row
        }
        fun receipts(key: String, fields: Set<String>) = list(key) { o ->
            require(o.keys().asSequence().toSet() == fields + "entryUid") { "来源凭据字段无效" }
            SourceReceipt(fields.associateWith { o.text(it) }, o.id("entryUid"))
        }
        val recognition = receipts("recognition", recognitionFields)
        val imports = receipts("imports", importFields)
        fun unique(values: List<String>) { require(values.distinct().size == values.size) { "标识重复" } }
        unique(books.map { it.id }); unique(accounts.map { it.id }); unique(categories.map { it.id }); unique(entries.map { it.uid })
        if(version==5)unique(resolvedCategories.mapNotNull { it.presetKey })
        unique(books.map { it.name }); unique(accounts.filterNot { it.archived }.map { it.name })
        if(version!=5)unique(categories.map { "${legacyCategoryBooks.getValue(it.id)}:${it.type.name}:${it.name}" })
        unique(recognition.map { it.fields.getValue("source_key") }); unique(recognition.map { it.entryUid })
        unique(imports.map { it.fields.getValue("source_key") })
        val bookIds = books.map { it.id }.toSet(); val accountMap = accounts.associateBy { it.id }; val categoryMap = categories.associateBy { it.id }
        require(categories.all { it.type != TransactionType.TRANSFER } && legacyCategoryBooks.values.all { it in bookIds }) { "账本引用无效" }
        entries.forEach { row ->
            require(row.bookId in bookIds) { "账单账本引用无效" }
            listOfNotNull(row.accountId, row.toAccountId).forEach { require(it in accountMap) { "账单账户引用无效" } }
            row.categoryId?.let { require(categoryMap[it]?.let { c -> (version==5 || legacyCategoryBooks[c.id]==row.bookId) && c.type == row.type } == true) { "账单分类引用无效" } }
        }
        val uids = entries.map { it.uid }.toSet()
        (recognition + imports).forEach { require(it.entryUid in uids) { "凭据缺少账单" } }
        fun receiptInteger(value: String): Long {
            require(value.length <= 20 && Regex("-?(0|[1-9][0-9]*)").matches(value)) { "来源整数格式无效" }
            return value.toLong()
        }
        recognition.forEach { r ->
            val f = r.fields
            require(f.getValue("evidence_hash").matches(Regex("[0-9a-f]{64}")) && f.getValue("source_key") == f.getValue("app_package") + ":" + f.getValue("external_id")) { "识别凭据无效" }
            require(receiptInteger(f.getValue("source_amount_minor")) > 0); receiptInteger(f.getValue("source_time"))
            require(TransactionType.valueOf(f.getValue("source_type")) != TransactionType.TRANSFER)
            val source = TransactionCandidate(f.getValue("app_package"), TransactionType.valueOf(f.getValue("source_type")), Money(f.getValue("source_amount_minor").toLong()),
                f.getValue("source_merchant"), f.getValue("external_id"), null, f.getValue("rule_version"), f.getValue("source_time").toLong())
            require(source.evidenceHash() == f.getValue("evidence_hash")) { "识别凭据与来源信息不符" }
        }
        imports.forEach { r ->
            val f = r.fields
            require(listOf("fingerprint", "identity_key", "file_hash").all { f.getValue(it).matches(Regex("[0-9a-f]{64}")) }) { "导入凭据校验值无效" }
            val prefix = f.getValue("fingerprint") + ":"
            require(f.getValue("source_key").startsWith(prefix) && receiptInteger(f.getValue("source_key").removePrefix(prefix)) >= 0 &&
                receiptInteger(f.getValue("source_row")) in 1..Int.MAX_VALUE.toLong()) { "导入凭据位置无效" }
        }
        val sourceAnchors = recognition.associateBy { it.fields.getValue("source_key") }
        entries.filter { it.source == "RECOGNIZED" }.forEach { row ->
            val raw = JSONObject(row.rawSource)
            if (raw.optString("recognition_format") in setOf("candidate-v1", "candidate-v2")) {
                val source = TransactionCandidate(raw.getString("app_package"), TransactionType.valueOf(raw.getString("source_type")),
                    Money(receiptInteger(raw.getString("source_amount_minor"))), raw.getString("source_merchant"), if (raw.isNull("external_id")) null else raw.getString("external_id"), null,
                    raw.getString("rule_version"), receiptInteger(raw.getString("source_time")))
                require(source.reliableKey == (if (raw.isNull("source_key")) null else raw.getString("source_key")) && source.evidenceHash() == raw.getString("evidence_hash")) { "记账来源信息不符" }
                if (source.reliableKey == null) {
                    require(raw.optString("recognition_format") == "candidate-v2" && !raw.has("duplicate_of_uid") && source.occurredAtMs != null) { "无编号记账来源无效" }
                    return@forEach
                }
                val anchor = sourceAnchors[source.reliableKey] ?: throw IllegalArgumentException("记账来源缺少识别凭据")
                if (raw.has("duplicate_of_uid")) require(raw.getString("duplicate_of_uid") == anchor.entryUid && row.uid != anchor.entryUid) { "重复账目来源引用无效" }
                else require(row.uid == anchor.entryUid && source.evidenceHash() == anchor.fields.getValue("evidence_hash")) { "记账来源与凭据不符" }
            }
        }
        val normalized=if(version==5)CategoryNormalization(resolvedCategories,resolvedCategories.associate { it.id to it.id }) else GlobalCategories.normalize(resolvedCategories)
        return ExchangeData(books, accounts, normalized.categories, entries.map { row -> row.copy(categoryId=row.categoryId?.let { normalized.aliases.getValue(it) }) }, recognition, imports, version as Int)
    }
    internal val recognitionFields = setOf("source_key", "evidence_hash", "app_package", "external_id", "rule_version", "source_amount_minor", "source_merchant", "source_type", "source_time")
    internal val importFields = setOf("source_key", "fingerprint", "identity_key", "file_hash", "sheet", "source_row")
}

fun LedgerStore.exportExchange(): ByteArray = transaction { db ->
    val entries = allRows(db, true); val catalog = catalog(db, entries)
    val uids = entries.associate { it.id to it.uid }
    fun receipts(table: String, fields: Set<String>) = db.rawQuery("SELECT * FROM $table ORDER BY source_key", null).use { c ->
        buildList { while (c.moveToNext()) add(SourceReceipt(fields.associateWith { c.getString(c.getColumnIndexOrThrow(it)) }, uids.getValue(c.getLong(c.getColumnIndexOrThrow("entry_id"))))) }
    }
    LedgerExchange.encode(ExchangeData(catalog.books, catalog.accounts, catalog.categories, entries,
        receipts("recognition_receipts", LedgerExchange.recognitionFields), receipts("import_receipts", LedgerExchange.importFields)))
}

private fun LedgerStore.exchangePlan(db: SQLiteDatabase, data: ExchangeData): ExchangePlan {
    val rows = allRows(db, true); val originalCatalog = catalog(db, rows)
    val fresh = CategoryPack.pristine(rows, originalCatalog)
    val replaceInitialBook = fresh && data.books.isNotEmpty() && data.books.none { it.id == DEFAULT_BOOK }
    val catalog = if (fresh) originalCatalog.copy(categories = emptyList(), accounts=emptyList(), books = if (replaceInitialBook) emptyList() else originalCatalog.books) else originalCatalog
    val conflicts = mutableListOf<String>(); val existing = rows.associateBy { it.uid }
    val effectiveBooks = if (fresh) catalog.books.map { old -> data.books.find { it.id == old.id } ?: old } else catalog.books
    data.books.forEach { value ->
        val old = catalog.books.find { it.id == value.id }
        if (old != null && old != value && !(fresh && value.id == DEFAULT_BOOK)) conflicts += "账本名称冲突"
        if (effectiveBooks.any { it.id != value.id && it.name == value.name }) conflicts += "账本重名"
    }
    data.accounts.forEach { value ->
        catalog.accounts.find { it.id == value.id }?.let { if (it.copy(balanceMinor = value.balanceMinor) != value) conflicts += "账户设置冲突" }
        if (catalog.accounts.any { it.id != value.id && !it.archived && !value.archived && it.name == value.name }) conflicts += "账户重名"
    }
    data.categories.forEach { value ->
        if(value.presetKey!=null && catalog.categories.any { it.id!=value.id && it.presetKey==value.presetKey })conflicts+="分类设置冲突"
        catalog.categories.find { it.id == value.id }?.let { old ->
            val comparable = if (data.schemaVersion == 1) value.copy(icon = old.icon, position = old.position, hidden = old.hidden, presetKey = old.presetKey)
                else if (data.schemaVersion < 4) value.copy(presetKey = old.presetKey) else value
            if (old != comparable) conflicts += "分类设置冲突"
        }
    }
    data.entries.forEach { value -> existing[value.uid]?.let { if (it.copy(id = 0) != value.copy(id = 0)) conflicts += "账单 ${value.uid.take(8)} 内容冲突" } }
    fun receipts(table: String, incoming: List<SourceReceipt>, fields: Set<String>) {
        incoming.forEach { r -> db.rawQuery("SELECT * FROM $table WHERE source_key=?", arrayOf(r.fields.getValue("source_key"))).use { c ->
            if (c.moveToFirst()) {
                val oldUid = rows.firstOrNull { it.id == c.getLong(c.getColumnIndexOrThrow("entry_id")) }?.uid
                if (oldUid != r.entryUid || fields.any { c.getString(c.getColumnIndexOrThrow(it)) != r.fields[it] }) conflicts += "来源凭据冲突"
            }
        } }
    }
    receipts("recognition_receipts", data.recognition, LedgerExchange.recognitionFields)
    receipts("import_receipts", data.imports, LedgerExchange.importFields)
    val skipped = data.entries.count { it.uid in existing }
    return ExchangePlan(data.entries.size - skipped, skipped, conflicts.distinct(), data.entries.count { !it.deleted })
}
fun LedgerStore.previewExchange(data: ExchangeData): ExchangePlan = transaction { exchangePlan(it, data) }
fun LedgerStore.commitExchange(data: ExchangeData): ImportResult = transaction { db ->
    val plan = exchangePlan(db, data)
    require(plan.conflicts.isEmpty()) { "数据文件与当前账本存在冲突" }
    val current = allRows(db, true)
    if (CategoryPack.pristine(current, catalog(db, current))) {
        db.delete("categories", null, null)
        db.delete("accounts",null,null)
        if (data.books.isNotEmpty() && data.books.none { it.id == DEFAULT_BOOK }) db.delete("books", "id=?", arrayOf(DEFAULT_BOOK))
    }
    data.books.find { it.id == DEFAULT_BOOK }?.let { db.update("books", ContentValues().apply { put("name", it.name) }, "id=?", arrayOf(DEFAULT_BOOK)) }
    data.books.forEach { b ->
        if (!db.hasId("books", b.id)) db.insertOrThrow("books", null, ContentValues().apply { put("id", b.id); put("name", b.name) })
        else if (b.id == DEFAULT_BOOK) db.update("books", ContentValues().apply { put("name", b.name) }, "id=?", arrayOf(b.id))
    }
    data.accounts.forEach { a -> if (!db.hasId("accounts", a.id)) db.insertOrThrow("accounts", null, ContentValues().apply {
        put("id", a.id); put("name", a.name); put("opening_minor", a.openingMinor.toString()); put("balance_known", if (a.balanceKnown) 1 else 0); put("archived", if (a.archived) 1 else 0)
    }) }
    data.categories.forEach { c -> if (!db.hasId("categories", c.id)) db.insertOrThrow("categories", null, ContentValues().apply {
        put("id", c.id); put("name", c.name); put("type", c.type.name)
        put("icon", c.icon); put("position", c.position); put("hidden", if (c.hidden) 1 else 0); put("preset_key", c.presetKey)
    }) }
    val known = allRows(db, true).associateBy { it.uid }.toMutableMap()
    data.entries.forEach { r -> if (r.uid !in known) {
        val id = insertDraft(db, r.draft(), r.uid, r.source, r.rawSource, r.revision, r.deleted, r.createdAtMs, r.updatedAtMs, allowArchived = true)
        known[r.uid] = r.copy(id = id)
    } }
    fun receipts(table: String, list: List<SourceReceipt>) {
        list.forEach { r ->
            val exists = db.rawQuery("SELECT 1 FROM $table WHERE source_key=?", arrayOf(r.fields.getValue("source_key"))).use { it.moveToFirst() }
            if (!exists) db.insertOrThrow(table, null, ContentValues().apply {
                r.fields.forEach { (key, value) -> put(key, value) }; put("entry_id", known.getValue(r.entryUid).id)
            })
        }
    }
    receipts("recognition_receipts", data.recognition); receipts("import_receipts", data.imports)
    if (selectionMemory(db).currentBookId == null) {
        val book = SelectionPolicy.resolve(catalog(db, allRows(db)), selectionMemory(db), TransactionType.EXPENSE).bookId
        db.update("selection_state", ContentValues().apply { put("book_id", book) }, "id=1", null)
    }
    if(!db.rawQuery("SELECT 1 FROM categories LIMIT 1",null).use { it.moveToFirst() })CategoryPack.ensure(db)
    else if(data.schemaVersion<5 && CategoryPack.legacySeeded(LedgerCatalog(data.books,data.categories,data.accounts)))CategoryPack.introduceNewIncome(db)
    ImportResult(plan.added, plan.skipped)
}
