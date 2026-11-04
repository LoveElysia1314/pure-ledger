package io.github.localledger.core

enum class TransactionType { EXPENSE, INCOME, TRANSFER }

/** A detached snapshot; no AccessibilityNodeInfo or Activity reference. */
data class PageSnapshot(val appPackage: String, val texts: List<String>, val capturedAtMs: Long)

data class TransactionCandidate(
    val appPackage: String,
    val type: TransactionType,
    val amount: Money,
    val merchant: String,
    val externalId: String?,
    val displayedTime: String?,
    val ruleVersion: String,
    val occurredAtMs: Long? = null,
) {
    init { require(amount.minor > 0); require(merchant.isNotBlank()) }
    val reliableKey: String? get() = externalId?.takeIf { it.isNotBlank() }?.let { "$appPackage:$it" }
    val temporaryKey: String get() = listOf(appPackage, type.name, amount.minor.toString(),
        merchant, displayedTime.orEmpty()).joinToString("\u001f")
}

sealed interface RecognitionResult {
    data class Recognized(val candidate: TransactionCandidate) : RecognitionResult
    data class NoMatch(val reason: String) : RecognitionResult
}

/** Synthetic-fixture baseline only. Current real-app compatibility is not established. */
class DetailPageParser {
    private val supported = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")

    fun parse(snapshot: PageSnapshot): RecognitionResult {
        if (snapshot.appPackage !in supported) return RecognitionResult.NoMatch("未覆盖应用")
        val texts = snapshot.texts.map(String::trim).filter(String::isNotEmpty)
        if (texts.none { it == "账单详情" || it == "交易详情" }) {
            return RecognitionResult.NoMatch("没有详情上下文")
        }
        fun values(labels: Set<String>) = texts.mapIndexedNotNull { index, text ->
            if (text in labels) texts.getOrNull(index + 1) else null
        }.distinct()
        val expenses = values(setOf("支出金额"))
        val incomes = values(setOf("收入金额"))
        if ((expenses.size == 1) == (incomes.size == 1) || expenses.size > 1 || incomes.size > 1) {
            return RecognitionResult.NoMatch("交易方向或金额有歧义")
        }
        val type = if (expenses.size == 1) TransactionType.EXPENSE else TransactionType.INCOME
        val rawAmount = (expenses + incomes).single()
        val amount = try { Money.parseCny(rawAmount) } catch (_: IllegalArgumentException) {
            return RecognitionResult.NoMatch("金额无效")
        } catch (_: ArithmeticException) { return RecognitionResult.NoMatch("金额超出精度或范围") }
        if (amount.minor <= 0) return RecognitionResult.NoMatch("金额需大于零")
        val merchants = values(setOf("商户名称", "交易对方"))
        if (merchants.size != 1) return RecognitionResult.NoMatch("交易对方缺失或有歧义")
        val ids = values(setOf("订单号", "交易单号", "交易流水号"))
        if (ids.size > 1) return RecognitionResult.NoMatch("交易编号有歧义")
        val times = values(setOf("支付时间", "收款时间"))
        if (times.size > 1) return RecognitionResult.NoMatch("交易时间有歧义")
        return RecognitionResult.Recognized(TransactionCandidate(snapshot.appPackage, type,
            amount, merchants.single(), ids.singleOrNull(), times.singleOrNull(), "fixture-detail-v1"))
    }
}

/** Prototype presentation gate; committed keys must later be persisted transactionally. */
class CandidateGate(private val mergeWindowMs: Long = 1_500) {
    init { require(mergeWindowMs >= 0) }
    private val committed = mutableSetOf<String>()
    private val recentlyPresented = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldPresent(candidate: TransactionCandidate, monotonicNowMs: Long): Boolean {
        if (candidate.reliableKey in committed) return false
        recentlyPresented.entries.removeAll { monotonicNowMs - it.value >= mergeWindowMs }
        val key = candidate.reliableKey ?: candidate.temporaryKey
        if (recentlyPresented.containsKey(key)) return false
        recentlyPresented[key] = monotonicNowMs
        return true
    }

    @Synchronized
    fun rememberCommitted(candidate: TransactionCandidate) {
        candidate.reliableKey?.let(committed::add)
    }

    @Synchronized
    fun cancel(candidate: TransactionCandidate) {
        recentlyPresented.remove(candidate.reliableKey ?: candidate.temporaryKey)
    }
}
