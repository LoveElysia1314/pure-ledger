package io.github.localledger.core

import java.time.ZoneId

/** Parses ordinary CNY merchant details from detached Chinese or English page text. */
class WeChatDetailParser(private val zone: ZoneId = ZoneId.of("Asia/Shanghai")) {
    private fun label(value: String) = PaymentText.label(value)
    private val times = setOf("支付时间", "收款时间", "payment time", "transaction time", "paid at", "time of payment", "time of transaction", "date and time", "date & time")
    private val numbers = setOf("交易单号", "交易流水号", "transaction id", "transaction no.", "transaction number", "transaction no", "transaction id.", "transaction order no.", "transaction order no", "transaction order number")
    private val merchants = setOf("商户名称", "商户全称", "交易对方", "收款方", "merchant", "payee", "counterparty", "merchant name", "merchant full name", "full merchant name", "recipient", "recipient name")
    private val context = setOf("账单详情", "交易详情", "支付成功", "交易成功", "transaction details", "bill details",
        "payment successful", "payment received", "transaction successful", "payment details", "payment succeeded", "transaction succeeded", "payment success", "received successfully")
    private val statuses = setOf("当前状态", "交易状态", "current status", "status")
    private val fields = times + numbers + merchants + statuses + setOf("商品", "商品说明", "收单机构", "支付方式", "商户单号", "币种",
        "products", "product", "acquirer", "payment method", "merchant order no.", "merchant order no", "currency")
    private val unsupported = setOf("转账", "转账时间", "退款", "退款时间", "已退款", "零钱提现", "提现",
        "transfer", "transfer time", "refund", "refunded", "refund time", "withdrawal", "refund successful", "payment failed", "transaction closed", "awaiting payment", "pending payment", "payment pending", "pending", "failed", "closed", "payment unsuccessful", "payment cancelled", "payment canceled", "refund in progress", "partially refunded", "transfer successful")
    private val signed = Regex("^[+-]\\s*(?:(?:CNY|RMB)\\s*|[¥￥])?\\s*(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d{1,2})?(?:\\s*元)?$", RegexOption.IGNORE_CASE)

    fun parse(snapshot: PageSnapshot): RecognitionResult {
        fun no(reason: String) = RecognitionResult.NoMatch(reason)
        if (snapshot.appPackage != "com.tencent.mm") return no("尚未覆盖该应用")
        val texts = snapshot.texts.map(PaymentText::clean).filter(String::isNotEmpty)
        val labels = texts.map(::label)
        if (labels.any { it in unsupported }) return no("转账、退款或提现需单独适配")
        if (labels.none { it in context }) return no("没有成功交易详情的上下文")
        fun values(keys: Set<String>) = texts.indices.filter { labels[it] in keys }.mapNotNull { texts.getOrNull(it + 1) }.distinct()
        if (PaymentText.unsupportedCurrency(texts)) return no("尚未覆盖该币种")
        val ids = values(numbers)
        if (ids.size != 1 || !Regex("[A-Za-z0-9][A-Za-z0-9._-]{5,127}").matches(ids.single())) return no("交易单号缺失或有歧义")
        val dates = values(times)
        if (dates.size != 1) return no("交易时间缺失或有歧义")
        val occurred = PaymentText.time(dates.single(), zone) ?: return no("交易时间格式尚未覆盖")
        val amounts = texts.withIndex().filter { signed.matches(it.value.replace('−', '-')) }
        if (amounts.size != 1) return no("交易金额缺失或有歧义")
        val entry = amounts.single()
        val raw = entry.value.replace('−', '-').replace(Regex("(?i)RMB"), "CNY")
        val negative = raw.startsWith('-')
        val unsigned = raw.drop(1).trim().replace(Regex("(?i)^CNY"), "CNY")
        val amount = try { Money.parseCny(unsigned) } catch (_: IllegalArgumentException) { return no("金额格式无效") }
            catch (_: ArithmeticException) { return no("金额精度或范围无效") }
        if (amount.minor <= 0) return no("金额必须大于零")
        val parties = values(merchants)
        if (parties.size > 1) return no("交易对方有歧义")
        fun validMerchant(value: String) = value.isNotBlank() && value.length <= 200 &&
            label(value) !in context + fields + unsupported && '\n' !in value
        val merchant = parties.singleOrNull() ?: run {
            val adjacent = buildList {
                texts.getOrNull(entry.index - 1)?.takeIf(::validMerchant)?.let(::add)
                // Merchant cards place the name between the amount and the first status field.
                if (texts.getOrNull(entry.index + 2)?.let(::label) in statuses) {
                    texts.getOrNull(entry.index + 1)?.takeIf(::validMerchant)?.let(::add)
                }
            }.distinct()
            if (adjacent.size > 1) return no("交易对方有歧义")
            adjacent.singleOrNull()
        }
        if (merchant == null || !validMerchant(merchant)) return no("交易对方缺失")
        return RecognitionResult.Recognized(TransactionCandidate(snapshot.appPackage,
            if (negative) TransactionType.EXPENSE else TransactionType.INCOME, amount, merchant,
            ids.single(), dates.single(), "wechat-detail-v3", occurred))
    }
}
