package io.github.localledger.core

data class EntryChoice(val bookId: String, val categoryId: String? = null, val accountId: String? = null,
                       val toAccountId: String? = null, val affectsBalance: Boolean = true)
data class RecentChoice(val type: TransactionType, val choice: EntryChoice, val order: Long)
data class SelectionMemory(val currentBookId: String? = DEFAULT_BOOK, val recent: List<RecentChoice> = emptyList(),
                           val defaultBookId: String? = null)
data class MerchantChoice(val appPackage: String, val merchant: String, val type: TransactionType, val choice: EntryChoice)

/** Selection only: no money, Android objects, storage writes or inferred account creation. */
object SelectionPolicy {
    fun resolve(catalog: LedgerCatalog, memory: SelectionMemory, type: TransactionType,
                preferredBook: String? = null, merchant: EntryChoice? = null, appPackage: String? = null): EntryChoice {
        val ids = catalog.books.map { it.id }.toSet()
        val latest = memory.recent.maxByOrNull { it.order }?.choice?.bookId
        val book = listOfNotNull(preferredBook, memory.currentBookId, merchant?.bookId, memory.defaultBookId, latest)
            .firstOrNull { it in ids } ?: catalog.books.firstOrNull()?.id ?: DEFAULT_BOOK
        val mapped = merchant
        val recent = memory.recent.filter { it.choice.bookId == book }
        val typed = recent.filter { it.type == type }.maxByOrNull { it.order }?.choice
        val ordinary = memory.recent.filter { it.type != TransactionType.TRANSFER }.maxByOrNull { it.order }?.choice
        val globalTyped = memory.recent.filter { it.type == type }.maxByOrNull { it.order }?.choice
        fun account(id: String?) = id?.takeIf { value -> catalog.activeAccounts.any { it.id == value } }
        val base = mapped ?: typed ?: globalTyped
        val appNames = when(appPackage) { "com.tencent.mm" -> setOf("微信", "wechat", "wechat pay"); "com.eg.android.AlipayGphone" -> setOf("支付宝", "alipay"); else -> emptySet() }
        val appAccount = catalog.activeAccounts.singleOrNull { it.name.trim().lowercase(java.util.Locale.ROOT) in appNames }?.id
        val from = if (merchant != null) merchant.accountId else if (type == TransactionType.TRANSFER) {
            if (globalTyped != null) globalTyped.accountId else ordinary?.accountId
        } else appAccount ?: ordinary?.accountId
        val source = account(from) ?: appAccount ?: account(ordinary?.accountId) ?: catalog.activeAccounts.firstOrNull()?.id
        return EntryChoice(book,
            if (type == TransactionType.TRANSFER) null else base?.categoryId?.takeIf { id -> catalog.categories.any { it.id == id && it.type == type && !it.hidden } },
            source, if (type == TransactionType.TRANSFER) account((merchant ?: globalTyped)?.toAccountId)?.takeIf { it != source } else null,
            base?.affectsBalance ?: true)
    }
}
