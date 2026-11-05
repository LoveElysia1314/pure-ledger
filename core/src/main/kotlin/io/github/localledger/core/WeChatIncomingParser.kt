package io.github.localledger.core

import java.time.ZoneId

/** Settled incoming personal payments, using their full bill rather than a chat/receipt summary. */
internal class WeChatIncomingParser(private val zone: ZoneId) {
    private val transferFrom = listOf(
        Regex("^转账-来自\\s*(.+)$"),
        Regex("^Transfer\\s*-?\\s*from\\s+(.+)$", RegexOption.IGNORE_CASE),
    )
    private val statusLabels = setOf("当前状态", "交易状态", "current status", "status")
    private val transferTimes = setOf("转账时间", "transfer time")
    private val receiptTimes = setOf("收款时间", "received time")
    private val transferNumbers = setOf("转账单号", "transfer order no.", "transfer order no", "transfer number")
    private val refundFields = setOf("退款时间", "退款金额", "退还时间", "refund time", "refund amount", "return time")
    private val amountText = Regex("^[+-]\\s*(?:(?:CNY|RMB)\\s*|[¥￥])?\\s*(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d{1,2})?(?:\\s*元)?$", RegexOption.IGNORE_CASE)

    /** Null means this is outside this parser's page family; incomplete family members must not fall through. */
    fun parse(snapshot: PageSnapshot): RecognitionResult? {
        if (snapshot.appPackage != "com.tencent.mm") return null
        val texts = snapshot.texts.map(PaymentText::clean).filter(String::isNotEmpty)
        val labels = texts.map(PaymentText::label)
        val senders = texts.flatMap { text -> transferFrom.mapNotNull { it.matchEntire(text)?.groupValues?.get(1)?.trim() } }.distinct()
        if (senders.isEmpty()) return null
        fun no(reason: String) = RecognitionResult.NoMatch(reason)
        fun values(keys: Set<String>) = texts.indices.filter { labels[it] in keys }.mapNotNull { texts.getOrNull(it + 1) }.distinct()
        if (senders.size != 1 || senders.single().length !in 1..200 || '\n' in senders.single()) return no("转账付款方缺失或有歧义")
        val states = values(statusLabels).map(PaymentText::label)
        if (states.size != 1 || states.single() !in setOf("已存入零钱", "deposited into balance") || labels.any { it in refundFields }) {
            return no("尚未确认转账已到账")
        }
        if (PaymentText.unsupportedCurrency(texts)) return no("尚未覆盖该币种")
        val amounts = texts.filter(amountText::matches)
        if (amounts.size != 1 || !amounts.single().startsWith('+')) return no("转账收入金额缺失或有歧义")
        val amount = try { Money.parseCny(amounts.single().drop(1).trim().replace(Regex("(?i)^RMB"), "CNY")) }
            catch (_: IllegalArgumentException) { return no("金额格式无效") }
            catch (_: ArithmeticException) { return no("金额精度或范围无效") }
        if (amount.minor <= 0) return no("金额必须大于零")
        val ids = values(transferNumbers)
        if (ids.size != 1 || !Regex("[A-Za-z0-9][A-Za-z0-9._-]{5,127}").matches(ids.single())) return no("转账单号缺失或有歧义")
        val received = values(receiptTimes)
        val sent = values(transferTimes)
        if (received.size != 1 || sent.size != 1) return no("转账或收款时间缺失或有歧义")
        val receivedAt = PaymentText.time(received.single(), zone) ?: return no("收款时间格式尚未覆盖")
        val sentAt = PaymentText.time(sent.single(), zone) ?: return no("转账时间格式尚未覆盖")
        if (receivedAt < sentAt) return no("收款时间早于转账时间")
        return RecognitionResult.Recognized(TransactionCandidate(snapshot.appPackage, TransactionType.INCOME,
            amount, senders.single(), ids.single(), received.single(), "wechat-transfer-income-v1", receivedAt))
    }
}
