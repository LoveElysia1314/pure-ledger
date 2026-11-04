package io.github.localledger.app.data

import io.github.localledger.core.*
import java.math.BigInteger

data class LedgerRow(
    val id: Long, val type: TransactionType, val amount: Money, val merchant: String,
    val occurredAtMs: Long, val note: String, val uid: String = "", val revision: Long = 0,
    val bookId: String = DEFAULT_BOOK, val categoryId: String? = null, val accountId: String? = null,
    val toAccountId: String? = null, val fee: Money = Money(0), val discount: Money = Money(0),
    val affectsBalance: Boolean = true, val source: String = "MANUAL", val rawSource: String = "{}",
    val deleted: Boolean = false,
    val createdAtMs: Long = 0, val updatedAtMs: Long = 0,
) {
    fun draft() = EntryDraft(type, amount, merchant, occurredAtMs, note, bookId, categoryId,
        accountId, toAccountId, fee, discount, affectsBalance)
}
data class SummaryBucket(val name: String, val type: TransactionType, val amount: BigInteger, val count: Int)
data class PeriodStatement(val key: String, val totals: CashFlow)
data class LedgerSnapshot(
    val rows: List<LedgerRow> = emptyList(), val count: Int = 0,
    val expense: BigInteger = BigInteger.ZERO, val income: BigInteger = BigInteger.ZERO,
    val transferCost: BigInteger = BigInteger.ZERO, val catalog: LedgerCatalog = LedgerCatalog(),
    val categories: List<SummaryBucket> = emptyList(), val months: List<SummaryBucket> = emptyList(),
    val query: LedgerQuery = LedgerQuery(),
    val selection: io.github.localledger.core.SelectionMemory = io.github.localledger.core.SelectionMemory(),
    val cashFlow: CashFlow = CashFlow(),
    val days: List<PeriodStatement> = emptyList(), val monthly: List<PeriodStatement> = emptyList(),
)
sealed interface SaveResult {
    data class Saved(val id: Long) : SaveResult
    data object AlreadySaved : SaveResult
    data object Conflict : SaveResult
}
