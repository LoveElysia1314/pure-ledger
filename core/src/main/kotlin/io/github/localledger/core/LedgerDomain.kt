package io.github.localledger.core

import java.math.BigInteger

const val DEFAULT_BOOK = "book-default"

data class EntryDraft(
    val type: TransactionType,
    val amount: Money,
    val merchant: String,
    val occurredAtMs: Long,
    val note: String = "",
    val bookId: String = DEFAULT_BOOK,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val fee: Money = Money(0),
    val discount: Money = Money(0),
    val affectsBalance: Boolean = true,
) {
    init {
        require(amount.minor >= 0) { "金额不能为负数" }
        require(merchant.length <= 200) { "交易对方不能超过 200 字" }
        require(note.length <= 10000) { "备注不能超过 10000 字" }
        require(bookId.isNotBlank()) { "请选择账本" }
        require(fee.minor >= 0 && discount.minor >= 0) { "手续费和优惠不能为负数" }
        if (type == TransactionType.TRANSFER) {
            require(amount.minor > 0) { "转出金额必须大于零" }
            require(accountId != null && toAccountId != null && accountId != toAccountId) { "请选择不同的转出和转入账户" }
            require(categoryId == null) { "转账不使用收支分类" }
            require(transferReceived.signum() >= 0) { "手续费不能超过转出金额与优惠之和" }
        } else {
            require(toAccountId == null && fee.minor == 0L && discount.minor == 0L) { "收支账单不能带转账字段" }
        }
    }
    /** The original export's amount is debited; fee and discount change the receiving amount. */
    val transferReceived: BigInteger get() = BigInteger.valueOf(amount.minor) - BigInteger.valueOf(fee.minor) + BigInteger.valueOf(discount.minor)

    fun accountEffects(): Map<String, BigInteger> {
        if (!affectsBalance) return emptyMap()
        val value = BigInteger.valueOf(amount.minor)
        return when (type) {
            TransactionType.EXPENSE -> accountId?.let { mapOf(it to -value) }.orEmpty()
            TransactionType.INCOME -> accountId?.let { mapOf(it to value) }.orEmpty()
            TransactionType.TRANSFER -> mapOf(accountId!! to -value, toAccountId!! to transferReceived)
        }
    }
}

data class LedgerBook(val id: String, val name: String)
data class LedgerCategory(val id: String, val type: TransactionType, val name: String,
                          val icon: String = CategoryPresets.symbol(name),
                          val position: Long = CategoryPresets.rank(name, type).toLong(), val hidden: Boolean = false, val presetKey: String? = null)
data class LedgerAccount(val id: String, val name: String,
                         val openingMinor: BigInteger, val balanceKnown: Boolean, val balanceMinor: BigInteger = openingMinor, val archived: Boolean = false)
data class LedgerCatalog(val books: List<LedgerBook> = emptyList(), val categories: List<LedgerCategory> = emptyList(),
                         val accounts: List<LedgerAccount> = emptyList()) {
    val activeAccounts: List<LedgerAccount> get() = accounts.filterNot { it.archived }
    fun entryAccounts(vararg selected: String?) = accounts.filter { !it.archived || it.id in selected }
}
data class LedgerQuery(val text: String = "", val bookId: String? = null, val type: TransactionType? = null,
                       val categoryId: String? = null, val accountId: String? = null,
                       val fromMs: Long? = null, val untilMs: Long? = null,
                       val minimumMinor: Long? = null, val maximumMinor: Long? = null) {
    init {
        require(text.length <= 200) { "搜索内容不能超过 200 字" }
        require(fromMs == null || untilMs == null || fromMs < untilMs) { "结束时间需晚于开始时间" }
        require(minimumMinor == null || minimumMinor >= 0) { "最低金额不能为负数" }
        require(maximumMinor == null || maximumMinor >= 0) { "最高金额不能为负数" }
        require(minimumMinor == null || maximumMinor == null || minimumMinor <= maximumMinor) { "最高金额需不小于最低金额" }
    }
    val hasConditions: Boolean get() = text.isNotBlank() || type != null || categoryId != null || accountId != null || minimumMinor != null || maximumMinor != null
    fun clearConditions() = copy(text = "", type = null, categoryId = null, accountId = null, minimumMinor = null, maximumMinor = null)
}
