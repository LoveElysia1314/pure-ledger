package io.github.localledger.app.data

import android.content.ContentValues
import io.github.localledger.core.*
import org.json.JSONObject

/** A confirmed popup is a save request. Its UUID absorbs retries, independently of the transaction number. */
fun LedgerStore.saveCandidate(entry: ConfirmedEntry, requestId: String): SaveResult = transaction { db ->
    require(requestId.isNotBlank()) { "记账请求无效" }
    val source = entry.source
    entryByUid(db, requestId)?.let { old ->
        val original = runCatching { JSONObject(old.rawSource) }.getOrNull()
        require(!old.deleted && old.draft() == entry.draft() && original?.optString("source_key")?.takeUnless { it.isEmpty() || it == "null" } == source.reliableKey &&
            original?.optString("evidence_hash") == source.evidenceHash()) { "本次记账已保存，请在账单中修改" }
        return@transaction SaveResult.Saved(old.id)
    }
    val previous = if (source.reliableKey == null) null else db.rawQuery("SELECT entry_id FROM recognition_receipts WHERE source_key=?", arrayOf(source.reliableKey)).use {
        if (it.moveToFirst()) it.getLong(0) else null
    }
    val previousUid = previous?.let { id ->
        db.rawQuery("SELECT uid FROM entries WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
    }
    val raw = JSONObject().apply {
        put("recognition_format", if (source.reliableKey == null) "candidate-v2" else "candidate-v1")
        put("source_key", source.reliableKey ?: JSONObject.NULL); put("evidence_hash", source.evidenceHash()); put("app_package", source.appPackage)
        put("external_id", source.externalId ?: JSONObject.NULL); put("source_amount_minor", source.amount.minor.toString()); put("source_merchant", source.merchant)
        put("source_type", source.type.name); put("source_time", source.occurredAtMs.toString()); put("rule_version", source.ruleVersion)
        if (previousUid != null) put("duplicate_of_uid", previousUid)
    }.toString()
    val id = insertDraft(db, entry.draft(), uid = requestId, source = "RECOGNIZED", raw = raw)
    if (previous == null && source.reliableKey != null) db.insertOrThrow("recognition_receipts", null, ContentValues().apply {
        put("source_key", source.reliableKey); put("evidence_hash", source.evidenceHash()); put("entry_id", id)
        put("app_package", source.appPackage); put("external_id", source.externalId); put("rule_version", source.ruleVersion)
        put("source_amount_minor", source.amount.minor); put("source_merchant", source.merchant)
        put("source_type", source.type.name); put("source_time", source.occurredAtMs!!)
    })
    rememberChoice(db, entry.draft(), source, currentBook = true)
    SaveResult.Saved(id)
}
