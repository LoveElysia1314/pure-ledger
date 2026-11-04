package io.github.localledger.core

import java.time.ZoneId

/** Successful ordinary CNY bills; discount lines are never the transaction amount. */
class AlipayDetailParser(private val zone: ZoneId = ZoneId.of("Asia/Shanghai")) {
    private val chinese = Regex("^(支出|收入)\\s*((?:[¥￥]\\s*)?[0-9,]+(?:\\.[0-9]{1,2})?)\\s*元$")
    private val english = Regex("^(expense|income|paid|received|amount paid|amount received|payment amount|payment received)\\s*[:：]?\\s*((?:CNY|RMB|[¥￥])?\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s*(?:CNY|RMB|yuan)?)$", RegexOption.IGNORE_CASE)
    private val signed = Regex("^([+-])\\s*((?:CNY|RMB|[¥￥])?\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s*(?:CNY|RMB|元|yuan)?)$", RegexOption.IGNORE_CASE)
    private val details = setOf("账单详情", "交易详情", "bill details", "transaction details", "payment details")
    private val success = setOf("支付成功", "交易成功", "收款成功", "payment successful", "payment succeeded", "transaction successful", "transaction succeeded", "payment received", "received successfully", "payment success")
    private val paidAwaitingReceipt = setOf("waiting confirmation of receipt", "waiting for confirmation of receipt", "awaiting confirmation of receipt", "等待确认收货")
    private val unsupported = setOf("转账", "退款", "退款成功", "已退款", "交易关闭", "支付失败", "等待付款", "提现", "transfer", "refund", "refunded", "refund successful", "withdrawal", "payment failed", "payment unsuccessful", "transaction closed", "pending", "pending payment", "awaiting payment", "payment pending", "closed", "failed", "unpaid", "not paid", "waiting for payment", "等待买家付款", "待付款", "未付款")
    private data class Header(val index: Int, val amount: String, val type: TransactionType)
    private fun direction(value: String) = when (PaymentText.label(value)) {
        "支出", "expense", "paid", "amount paid", "payment amount" -> TransactionType.EXPENSE
        "收入", "income", "received", "amount received", "payment received" -> TransactionType.INCOME
        else -> null
    }
    private fun money(value: String): Money = Money.parseCny(PaymentText.clean(value)
        .replace(Regex("(?i)^(?:cny|rmb)\\s*"), "").replace(Regex("(?i)\\s*(?:cny|rmb|yuan)$"), ""))
    fun parse(snapshot: PageSnapshot): RecognitionResult {
        fun no(reason: String) = RecognitionResult.NoMatch(reason)
        if (snapshot.appPackage != "com.eg.android.AlipayGphone") return no("尚未覆盖该应用")
        val texts = snapshot.texts.map(PaymentText::clean).filter(String::isNotEmpty)
        val labels = texts.map(PaymentText::label)
        val awaitingReceipt = labels.any { it in paidAwaitingReceipt }
        if (labels.none { it in details } || labels.none { it in success } && !awaitingReceipt) return no("没有成功交易详情的上下文")
        if (labels.any { it in unsupported }) return no("该交易状态需单独适配")
        if (PaymentText.unsupportedCurrency(texts)) return no("尚未覆盖该币种")
        fun values(keys: Set<String>) = texts.indices.filter { labels[it] in keys }.mapNotNull { texts.getOrNull(it+1) }.distinct()
        val headers = texts.withIndex().mapNotNull { (index, value) ->
            chinese.matchEntire(value)?.let { return@mapNotNull Header(index, it.groupValues[2], direction(it.groupValues[1])!!) }
            english.matchEntire(value)?.let { return@mapNotNull Header(index, it.groupValues[2], direction(it.groupValues[1])!!) }
            val type = direction(value)
            if (type != null && labels[index] !in success) texts.getOrNull(index+1)?.let { next ->
                if (runCatching { money(next).minor > 0 }.getOrDefault(false)) return@mapNotNull Header(index, next, type)
            }
            null
        }.toMutableList()
        if (headers.isEmpty()) {
            val status = labels.indexOfFirst { it in success }
            texts.take(status.coerceAtLeast(0)).forEachIndexed { index, text -> signed.matchEntire(text)?.let {
                headers += Header(index, it.groupValues[2], if (it.groupValues[1]=="-") TransactionType.EXPENSE else TransactionType.INCOME)
            } }
        }
        if (headers.size != 1) return no("收支金额缺失或有歧义")
        val header = headers.single()
        // Buyer expense has already been paid; receipt confirmation does not establish seller income.
        if (awaitingReceipt && header.type != TransactionType.EXPENSE) return no("该交易状态需单独适配")
        val amount = try { money(header.amount) } catch (_: IllegalArgumentException) { return no("金额格式无效") }
            catch (_: ArithmeticException) { return no("金额精度或范围无效") }
        if (amount.minor <= 0) return no("金额必须大于零")
        val dates = values(setOf("支付时间", "付款时间", "收款时间", "payment time", "transaction time", "paid at", "time of payment", "date and time", "date & time"))
        if (dates.size != 1) return no("交易时间缺失或有歧义")
        val occurred = PaymentText.time(dates.single(), zone) ?: return no("交易时间格式尚未覆盖")
        val ids = values(setOf("订单号", "交易号", "交易单号", "支付宝交易号", "order no.", "order no", "order number", "transaction no.", "transaction no", "transaction id", "transaction number", "alipay transaction no.", "alipay transaction id"))
        if (ids.size > 1 || ids.any { !Regex("[A-Za-z0-9][A-Za-z0-9._-]{5,127}").matches(it) }) return no("交易编号有歧义")
        val parties = values(setOf("商户名称", "商户全称", "交易对方", "收款方", "商家名称", "merchant", "merchant name", "merchant full name", "full merchant name", "counterparty", "payee", "recipient", "recipient name"))
        if (parties.size > 1) return no("交易对方有歧义")
        val merchant = parties.singleOrNull() ?: texts.getOrNull(header.index-1)
        if (merchant.isNullOrBlank() || merchant.length > 200 || PaymentText.label(merchant) in details+success || merchant.contains('\n')) return no("交易对方缺失")
        return RecognitionResult.Recognized(TransactionCandidate(snapshot.appPackage, header.type,
            amount, merchant, ids.singleOrNull(), dates.single(), "alipay-bill-v2", occurred))
    }
}

class PaymentDetailParser {
    private val wechat = WeChatDetailParser()
    private val alipay = AlipayDetailParser()
    fun parse(snapshot: PageSnapshot): RecognitionResult = when(snapshot.appPackage) {
        "com.tencent.mm" -> wechat.parse(snapshot)
        "com.eg.android.AlipayGphone" -> alipay.parse(snapshot)
        else -> RecognitionResult.NoMatch("尚未覆盖该应用")
    }
}
