package io.github.localledger.core

import java.time.Instant
import kotlin.test.*

class WeChatIncomingParserTest {
    private val parser = PaymentDetailParser()
    private fun page(english: Boolean = false, id: String = "SYNTHETIC-TRANSFER-0001") = PageSnapshot("com.tencent.mm",
        if (english) listOf("转账-来自合成付款方", "+42.80", "Current Status", "Deposited into Balance",
            "Transfer Description", "微信转账", "Transfer Time", "2026/11/3 08:00:00",
            "Received Time", "2026/11/4 09:08:07", "Transfer Order No.", id,
            "Transaction Services", "Pinned to chat", "Request for online transfer voucher", "View transaction records",
            "This service is provided by Tenpay", "All transactions")
        else listOf("转账-来自合成付款方", "+42.80", "当前状态", "已存入零钱", "转账说明", "微信转账",
            "转账时间", "2026年11月3日 08:00:00", "收款时间", "2026年11月4日 09:08:07", "转账单号", id,
            "账单服务", "定位到聊天位置", "申请转账电子凭证", "查看往来交易", "本服务由财付通提供", "全部账单"), 0)
    private fun parsed(page: PageSnapshot) = assertIs<RecognitionResult.Recognized>(parser.parse(page)).candidate
    private fun change(page: PageSnapshot, old: String, new: String) = page.copy(texts = page.texts.map { if (it == old) new else it })

    @Test fun chineseIncomeUsesReceiptTimeRatherThanTheEarlierTransferTime() {
        val candidate = parsed(page())
        assertEquals(TransactionType.INCOME, candidate.type)
        assertEquals(4280L, candidate.amount.minor)
        assertEquals("合成付款方", candidate.merchant)
        assertEquals("SYNTHETIC-TRANSFER-0001", candidate.externalId)
        assertEquals(Instant.parse("2026-11-04T01:08:07Z").toEpochMilli(), candidate.occurredAtMs)
        assertEquals("2026年11月4日 09:08:07", candidate.displayedTime)
    }

    @Test fun englishFieldsAndChineseGeneratedTitleKeepTheSameIdentityAndEvidence() {
        val chinese = parsed(page())
        val english = parsed(page(true))
        assertEquals(chinese.reliableKey, english.reliableKey)
        assertEquals(chinese.evidenceHash(), english.evidenceHash())
        assertEquals(chinese.occurredAtMs, english.occurredAtMs)
        assertEquals(chinese.merchant, english.merchant)
        assertEquals(chinese.amount, english.amount)
    }

    @Test fun englishGeneratedTitleAndCaseInsensitiveLabelsUseTheSameSender() {
        val english = page(true).copy(texts = page(true).texts.map {
            when (it) {
                "转账-来自合成付款方" -> "Transfer from Synthetic Sender"
                "Current Status" -> "CURRENT STATUS:"
                "Deposited into Balance" -> "DEPOSITED INTO BALANCE"
                else -> it
            }
        })
        assertEquals("Synthetic Sender", parsed(english).merchant)
    }

    @Test fun repeatedEventsKeepIdentityAndTwoSimilarTransfersRemainDistinct() {
        val first = parsed(page())
        assertEquals(first, parsed(page()))
        val second = parsed(page(id = "SYNTHETIC-TRANSFER-0002"))
        assertEquals(first.amount, second.amount)
        assertEquals(first.merchant, second.merchant)
        assertEquals(first.occurredAtMs, second.occurredAtMs)
        assertNotEquals(first.reliableKey, second.reliableKey)
    }

    @Test fun pendingRefundedFailedAndOutgoingStatesNeverBecomeIncome() {
        for (english in listOf(false, true)) {
            val page = page(english)
            val old = if (english) "Deposited into Balance" else "已存入零钱"
            for (state in listOf("待收款", "待对方收款", "已退还", "已退款", "已过期", "转账成功",
                "Waiting to be received", "Pending", "Refunded", "Returned", "Expired", "Transfer successful", "Failed")) {
                assertIs<RecognitionResult.NoMatch>(parser.parse(change(page, old, state)))
            }
        }
    }

    @Test fun anIncomingTitleAndExplicitFinalStatusAreBothRequired() {
        for (title in listOf("转账-给合成收款方", "Transfer to Synthetic Recipient", "合成付款方", "转账-来自")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(change(page(), "转账-来自合成付款方", title)))
        }
        assertIs<RecognitionResult.NoMatch>(parser.parse(change(page(), "当前状态", "商品")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf("交易状态", "待收款"))))
    }

    @Test fun chatAndAcceptanceSummariesWithoutPayerAndNumberWaitForFullDetails() {
        val summaries = listOf(
            listOf("你已收款，资金已存入零钱", "￥42.80", "零钱余额", "转账时间", "2026年11月3日 08:00:00",
                "收款时间", "2026年11月4日 09:08:07", "账单详情"),
            listOf("You've accepted the transfer. The money has been deposited to your Balance.", "￥42.80", "Amount in Balance",
                "Transfer Time", "11/03/2026, 08:00:00", "Receipt Time", "11/04/2026, 09:08:07", "Transaction Details"),
            listOf("合成付款方", "￥42.80", "已收款", "微信转账"),
        )
        for (texts in summaries) assertIs<RecognitionResult.NoMatch>(parser.parse(PageSnapshot("com.tencent.mm", texts, 0)))
    }

    @Test fun missingConflictingOrInvalidNumbersAreNotInvented() {
        val page = page()
        for (id in listOf("", "123", "invalid id")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(change(page, "SYNTHETIC-TRANSFER-0001", id)))
        }
        assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts = page.texts.take(page.texts.indexOf("转账单号")))))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts = page.texts + listOf("转账单号", "SYNTHETIC-TRANSFER-0002"))))
    }

    @Test fun invalidMissingAmbiguousAndReversedReceiptTimesAreRejected() {
        val page = page()
        for (time in listOf("11/04/2026 09:08:07", "2026年11月31日 09:08:07", "2026年11月2日 09:08:07", "")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(change(page, "2026年11月4日 09:08:07", time)))
        }
        assertIs<RecognitionResult.NoMatch>(parser.parse(change(page, "收款时间", "记录时间")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(change(page, "转账时间", "记录时间")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts = page.texts + listOf("收款时间", "2026年11月4日 10:08:07"))))
    }

    @Test fun negativeZeroUnsignedOverpreciseForeignAndAmbiguousAmountsAreRejected() {
        for (amount in listOf("-42.80", "42.80", "+0.00", "+42.801", "+USD 42.80", "+$42.80")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(change(page(), "+42.80", amount)))
        }
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + "+1.00")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf("币种", "USD"))))
    }

    @Test fun conflictingPayersAndRefundFieldsAreRejected() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + "转账-来自另一位合成付款方")))
        for (field in listOf("退款时间", "退款金额", "退还时间", "Refund Amount")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts = page().texts + listOf(field, "1.00"))))
        }
    }

    @Test fun onlyWeChatIsAllowed() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(appPackage = "another.app")))
    }
}
