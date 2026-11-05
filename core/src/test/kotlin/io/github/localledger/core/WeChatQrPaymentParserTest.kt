package io.github.localledger.core

import java.time.Instant
import kotlin.test.*

class WeChatQrPaymentParserTest {
    private val parser = PaymentDetailParser()
    private fun page(id: String = "SYNTHETIC-QR-0001") = PageSnapshot("com.tencent.mm", listOf(
        "返回", "合成收款方", "留言", "主页", "交易详情", "-12.34", "扫二维码付款-给合成收款方",
        "当前状态", "支付成功", "收款方备注", "二维码收款", "支付方式", "零钱",
        "转账时间", "2026年11月05日 18:15:26", "转账单号", id,
        "交易服务", "对订单有疑惑", "发起群收款", "本服务由财付通提供"), 0)
    private fun parsed(page: PageSnapshot = page()) = assertIs<RecognitionResult.Recognized>(parser.parse(page)).candidate
    private fun replace(old: String, new: String) = page().copy(texts = page().texts.map { if (it == old) new else it })

    @Test fun outgoingQrPaymentUsesItsExplicitRecipientAndTransferFields() {
        val candidate = parsed()
        assertEquals(TransactionType.EXPENSE, candidate.type)
        assertEquals(1234L, candidate.amount.minor)
        assertEquals("合成收款方", candidate.merchant)
        assertEquals("SYNTHETIC-QR-0001", candidate.externalId)
        assertEquals(Instant.parse("2026-11-05T10:15:26Z").toEpochMilli(), candidate.occurredAtMs)
        assertEquals("wechat-qr-payment-v1", candidate.ruleVersion)
        // Header cards and optional remarks never replace the actual payment recipient.
        assertEquals(candidate, parsed(replace("合成收款方", "另一个合成名片")))
    }

    @Test fun ordinaryTransferOrMissingQrRecipientDoesNotBecomeAnExpense() {
        for (description in listOf("转账给合成好友", "微信转账", "扫二维码付款-给", "二维码收款")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(replace("扫二维码付款-给合成收款方", description)))
        }
    }

    @Test fun explicitSuccessfulStatusIsRequiredEvenWithTheDetailTitle() {
        for (status in listOf("待支付", "支付失败", "交易关闭", "待收款", "已退款", "已部分退款", "退款处理中", "转账成功")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(replace("支付成功", status)))
        }
        assertIs<RecognitionResult.NoMatch>(parser.parse(replace("当前状态", "商品说明")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf("交易状态", "支付失败"))))
    }

    @Test fun refundEvidenceCannotHideBehindASuccessfulPaymentStatus() {
        for (refund in listOf("退款", "退款金额", "退款成功", "已全额退款", "已部分退款", "退款处理中")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf(refund, "1.00"))))
        }
    }

    @Test fun positiveAmountForeignCurrencyAndAmbiguousAmountsAreRejected() {
        for (amount in listOf("+12.34", "12.34", "-12.345", "-0.00", "-USD 12.34")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(replace("-12.34", amount)))
        }
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + "-1.00")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf("币种", "USD"))))
    }

    @Test fun missingOrConflictingTransferNumberAndTimeWaitForACompletePage() {
        for (field in listOf("转账时间", "转账单号")) {
            val position = page().texts.indexOf(field)
            assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts.take(position))))
            val alternative = if (field == "转账时间") "2026年11月05日 18:16:26" else "SYNTHETIC-QR-0002"
            assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf(field, alternative))))
        }
        assertIs<RecognitionResult.NoMatch>(parser.parse(replace("2026年11月05日 18:15:26", "2026年11月31日 18:15:26")))
    }

    @Test fun conflictingExplicitRecipientsAreNotGuessed() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + "扫二维码付款-给另一位合成收款方")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf("收款方", "另一位合成收款方"))))
    }

    @Test fun repeatsKeepIdentityAndSimilarPaymentsWithAnotherIdStayDistinct() {
        val first = parsed()
        assertEquals(first, parsed())
        val second = parsed(page("SYNTHETIC-QR-0002"))
        assertEquals(first.merchant, second.merchant)
        assertEquals(first.amount, second.amount)
        assertNotEquals(first.reliableKey, second.reliableKey)
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(appPackage = "other.app")))
    }
}
