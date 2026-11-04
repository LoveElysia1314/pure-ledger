package io.github.localledger.core

import java.time.Instant
import kotlin.test.*

class WeChatDetailParserTest {
    private val parser = WeChatDetailParser()
    private fun chinese(id: String = "SYNTHETIC-0001") = PageSnapshot("com.tencent.mm", listOf(
        "合成商户", "-12.34", "当前状态", "支付成功", "支付时间", "2026年10月31日 12:30:00", "交易单号", id), 0)
    private fun parsed(page: PageSnapshot) = assertIs<RecognitionResult.Recognized>(parser.parse(page)).candidate

    @Test fun chinesePaymentDetailWithoutToolbarTextIsRecognized() {
        val candidate = parsed(chinese())
        assertEquals(1234L, candidate.amount.minor)
        assertEquals(TransactionType.EXPENSE, candidate.type)
        assertEquals("合成商户", candidate.merchant)
        assertEquals(Instant.parse("2026-10-31T04:30:00Z").toEpochMilli(), candidate.occurredAtMs)
    }
    @Test fun syntheticEnglishLabelsAndTextualDateWork() {
        val page = PageSnapshot("com.tencent.mm", listOf("Transaction Details", "Synthetic merchant", "-CNY 1,234.56",
            "Status", "Payment Successful", "Transaction Time", "Oct 31, 2026 12:30:00", "Transaction ID", "SYNTHETIC-EN-0001"), 0)
        assertEquals(123456L, parsed(page).amount.minor)
    }
    @Test fun positiveAmountIsIncome() {
        val page = chinese().copy(texts = chinese().texts.map { if (it == "-12.34") "+12.34" else it })
        assertEquals(TransactionType.INCOME, parsed(page).type)
    }
    @Test fun newerMerchantDetailUsesFullNameBelowAmount() {
        val page = PageSnapshot("com.tencent.mm", listOf("交易详情", "-12.34", "合成门店", "当前状态", "支付成功",
            "支付时间", "2026年11月1日 12:30:00", "商品", "合成商品", "商户全称", "合成商户有限公司",
            "交易单号", "SYNTHETIC-NEW-0001", "商户单号", "SYNTHETIC-ORDER-0001"), 0)
        assertEquals("合成商户有限公司", parsed(page).merchant)
        assertEquals(1234L, parsed(page).amount.minor)
        assertNotEquals(parsed(page).reliableKey, parsed(page.copy(texts = page.texts.map {
            if (it == "SYNTHETIC-NEW-0001") "SYNTHETIC-NEW-0002" else it
        })).reliableKey)
        assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts = page.texts.dropLast(4))))
    }
    private fun merchantCard(id: String = "SYNTHETIC-CARD-0001") = PageSnapshot("com.tencent.mm", listOf(
        "返回", "更多", "合成商店", "喜欢", "小程序", "合成服务入口", "服务", "会员", "交易详情",
        "-42.80", "合成商店", "Current Status", "Payment successful", "Payment Time", "2026/11/02 09:08:07",
        "Products", "合成商品", "Acquirer", "合成收单机构", "Payment Method", "Balance",
        "Transaction Order No.", id, "Merchant Order No.", "SYNTHETIC-MERCHANT-0001", "Transaction Services"), 0)

    @Test fun merchantCardReadsNameBelowAmountWithoutAFullNameField() {
        val candidate = parsed(merchantCard())
        assertEquals("合成商店", candidate.merchant)
        assertEquals(4280L, candidate.amount.minor)
        assertEquals(TransactionType.EXPENSE, candidate.type)
        assertEquals("SYNTHETIC-CARD-0001", candidate.externalId)
        assertEquals(Instant.parse("2026-11-02T01:08:07Z").toEpochMilli(), candidate.occurredAtMs)
    }
    @Test fun merchantCardSupportsChineseLabelsAndAnOmittedToolbarTitle() {
        val page = merchantCard().copy(texts = merchantCard().texts.drop(9).map {
            when (it) {
                "Current Status" -> "当前状态"
                "Payment successful" -> "支付成功"
                "Payment Time" -> "支付时间"
                "Transaction Order No." -> "交易单号"
                else -> it
            }
        })
        assertEquals("合成商店", parsed(page).merchant)
    }
    @Test fun merchantCardCannotTreatAStatusLabelOrProductAsTheMissingMerchant() {
        val page = merchantCard()
        val amountIndex = page.texts.indexOf("-42.80")
        val missing = page.copy(texts = page.texts.filterIndexed { index, _ -> index != amountIndex + 1 })
        assertIs<RecognitionResult.NoMatch>(parser.parse(missing))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts = page.texts.map {
            if (it == "Current Status") "Products" else it
        })))
    }
    @Test fun conflictingHeaderMerchantsAreNotGuessed() {
        val page = merchantCard()
        assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts = page.texts.map {
            if (it == "交易详情") "另一个合成商户" else it
        })))
    }
    @Test fun explicitMerchantFieldStillTakesPriorityOverCardTitle() {
        val page = merchantCard().copy(texts = merchantCard().texts + listOf("商户全称", "合成商户有限公司"))
        assertEquals("合成商户有限公司", parsed(page).merchant)
    }
    @Test fun merchantCardRepeatsKeepIdentityAndSimilarPaymentsRemainDistinct() {
        val first = parsed(merchantCard())
        assertEquals(first, parsed(merchantCard()))
        val second = parsed(merchantCard("SYNTHETIC-CARD-0002"))
        assertNotEquals(first.reliableKey, second.reliableKey)
        assertEquals(first.amount, second.amount)
        assertEquals(first.merchant, second.merchant)
    }
    @Test fun sameMerchantAndAmountWithDifferentNumbersStayDifferent() {
        val a = parsed(chinese()); val b = parsed(chinese("SYNTHETIC-0002"))
        assertNotEquals(a.reliableKey, b.reliableKey)
    }
    @Test fun balanceOrShoppingPageDoesNotBecomeATransaction() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(PageSnapshot("com.tencent.mm", listOf("余额", "-12.34"), 0)))
    }
    @Test fun incompletePageWaitsForTransactionId() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(chinese().copy(texts = chinese().texts.dropLast(1))))
    }
    @Test fun multipleAmountsAreRejected() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(chinese().copy(texts = chinese().texts + "-2.00")))
    }
    @Test fun unsupportedTransferAndRefundAreNotMisclassified() {
        for (kind in listOf("退款", "转账", "Refund", "Transfer")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(chinese().copy(texts = chinese().texts + kind)))
        }
    }
    @Test fun unsupportedCurrencyIsRejected() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(chinese().copy(texts = chinese().texts + listOf("Currency", "USD"))))
    }
    @Test fun ambiguousDateOrExcessPrecisionIsRejected() {
        for ((old, replacement) in listOf("2026年10月31日 12:30:00" to "10/11/2026 12:30:00", "-12.34" to "-12.345")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(chinese().copy(texts = chinese().texts.map { if (it == old) replacement else it })))
        }
    }
    @Test fun otherAppsAreNotAccidentallySupported() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(chinese().copy(appPackage = "other.app")))
    }
}
