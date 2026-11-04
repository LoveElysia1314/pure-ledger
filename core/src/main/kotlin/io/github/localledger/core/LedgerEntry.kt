package io.github.localledger.core

import java.security.MessageDigest

/** A user-confirmed ordinary CNY transaction, retaining its original recognition evidence. */
data class ConfirmedEntry(
    val source: TransactionCandidate,
    val amount: Money = source.amount,
    val merchant: String = source.merchant,
    val type: TransactionType = source.type,
    val note: String = "",
    val bookId: String = DEFAULT_BOOK,
    val accountId: String? = null,
    val categoryId: String? = null,
    val occurredAtMs: Long = source.occurredAtMs ?: 0,
    val affectsBalance: Boolean = true,
) {
    init {
        require(source.occurredAtMs != null) { "交易时间缺失，不能保存" }
        require(amount.minor > 0) { "金额必须大于零" }
        require(merchant.isNotBlank() && merchant.length <= 200) { "交易对方需为 1–200 字" }
        require(note.length <= 10000) { "备注不能超过 10000 字" }
        require(type != TransactionType.TRANSFER && source.type != TransactionType.TRANSFER) {
            "转账需要指定转出与转入账户"
        }
    }
    fun draft() = EntryDraft(type, amount, merchant, occurredAtMs, note, bookId, categoryId, accountId, affectsBalance = affectsBalance)
}

/** Detect conflicting reuse of a source ID, independently of edits made at confirmation. */
fun TransactionCandidate.evidenceHash(): String {
    val parts = listOf(appPackage, externalId.orEmpty(), type.name, amount.minor.toString(),
        merchant, occurredAtMs?.toString().orEmpty())
    val canonical = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
