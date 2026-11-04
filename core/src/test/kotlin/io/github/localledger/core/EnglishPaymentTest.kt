package io.github.localledger.core

import java.time.Instant
import kotlin.test.*

class EnglishPaymentTest {
    private val parser = PaymentDetailParser()
    private fun wechat(date: String = "November 1, 2026 1:05:09 PM", id: String = "EN-WX-000001") = PageSnapshot("com.tencent.mm", listOf(
        "Payment Details", "-CNY 1,234.56", "Synthetic shop", "Current Status:", "Payment Successful", "Payment Time:", date,
        "Merchant Full Name", "Synthetic Merchant Ltd", "Transaction ID:", id, "Merchant Order No.", "MERCHANT-0001"),0)
    private fun alipay(date: String = "1 November 2026, 13:05:09", id: String? = null) = PageSnapshot("com.eg.android.AlipayGphone", listOf(
        "Bill Details", "Synthetic Merchant Ltd", "Expense CNY 1,234.56", "Payment Successful", "Discount", "-0.20", "Payment Time", date,
        "Payment Method", "Synthetic card") + if (id==null) emptyList() else listOf("Order No.",id,"Merchant Order No.","MERCHANT-0001"),0)
    private fun parsed(p: PageSnapshot) = assertIs<RecognitionResult.Recognized>(parser.parse(p)).candidate
    @Test fun fullEnglishWeChatLabelsPreserveIdMerchantTimeAndExactAmount() {
        val a = parsed(wechat())
        assertEquals(123456L,a.amount.minor);assertEquals("EN-WX-000001",a.externalId);assertEquals("Synthetic Merchant Ltd",a.merchant)
        assertEquals(Instant.parse("2026-11-01T05:05:09Z").toEpochMilli(),a.occurredAtMs)
        assertEquals(TransactionType.EXPENSE,a.type)
    }
    @Test fun textualMonthsYearFirstSlashesAndMinutePrecisionAreUnambiguous() {
        for (date in listOf("Nov 1, 2026 13:05:09","november 1, 2026 1:05:09 pm", "1 Nov 2026, 13:05:09", "2026/11/1 13:05:09")) {
            assertEquals(parsed(wechat()).occurredAtMs,parsed(wechat(date)).occurredAtMs)
        }
        assertEquals(Instant.parse("2026-11-01T05:05:00Z").toEpochMilli(),parsed(wechat("November 1, 2026 13:05")).occurredAtMs)
    }
    @Test fun repeatedSnapshotsAreStableButTwoSimilarPaymentsKeepTheirIdentity() {
        val a=parsed(wechat());assertEquals(a,parsed(wechat()))
        assertNotEquals(a.reliableKey,parsed(wechat(id="EN-WX-000002")).reliableKey)
        val b=parsed(alipay());assertEquals(b.evidenceHash(),parsed(alipay()).evidenceHash())
        assertNotEquals(b.evidenceHash(),parsed(alipay("1 November 2026, 13:05:10")).evidenceHash())
    }
    @Test fun englishAlipaySeparatesDiscountAndKeepsOptionalRealOrderId() {
        val a=parsed(alipay());assertEquals(123456L,a.amount.minor);assertNull(a.externalId);ConfirmedEntry(a)
        assertEquals("EN-ALI-000001",parsed(alipay(id="EN-ALI-000001")).externalId)
        val income=parsed(alipay().copy(texts=alipay().texts.map { if(it=="Expense CNY 1,234.56") "Income RMB 1,234.56" else it }))
        assertEquals(TransactionType.INCOME,income.type)
    }
    @Test fun englishAlipaySignedHeaderAndSplitAmountLabelsAreSupported() {
        val page=alipay()
        for (amount in listOf(listOf("-¥1,234.56"),listOf("Expense","CNY 1,234.56"),listOf("Amount paid: 1,234.56 CNY"))) {
            val index=page.texts.indexOf("Expense CNY 1,234.56")
            val p=page.copy(texts=page.texts.take(index)+amount+page.texts.drop(index+1))
            assertEquals(123456L,parsed(p).amount.minor);assertEquals(TransactionType.EXPENSE,parsed(p).type)
        }
    }
    @Test fun languageChangesDoNotChangeTransactionEvidence() {
        val en=parsed(wechat())
        val zh=parsed(PageSnapshot("com.tencent.mm",listOf("账单详情","-1234.56","支付成功","支付时间","2026-11-01 13:05:09","商户全称","Synthetic Merchant Ltd","交易单号","EN-WX-000001"),0))
        assertEquals(en.evidenceHash(),zh.evidenceHash())
    }
    @Test fun pendingFailedRefundAndTransferScreensAreNotOrdinaryBills() {
        for (page in listOf(wechat(),alipay())) for (state in listOf("Pending", "Payment Failed", "Refund Successful", "Transfer", "Awaiting Payment")) {
            assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts=page.texts+state)))
        }
    }
    @Test fun foreignCurrenciesBadDatesAndDecimalCommaAmountsAreRejected() {
        for (page in listOf(wechat(),alipay())) {
            for(extra in listOf(listOf("Currency","USD"),listOf("Currency:","HKD"))) assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts=page.texts+extra)))
        }
        for(date in listOf("01/11/2026 13:05:09","November 31, 2026 13:05:09","November 1, 2026 13:05:09 GMT")) assertIs<RecognitionResult.NoMatch>(parser.parse(wechat(date)))
        assertIs<RecognitionResult.NoMatch>(parser.parse(alipay().copy(texts=alipay().texts.map { if(it=="Expense CNY 1,234.56") "Expense CNY 1.234,56" else it })))
    }
    @Test fun balancesShoppingPagesAndConflictingNumbersAreRejected() {
        assertIs<RecognitionResult.NoMatch>(parser.parse(PageSnapshot("com.tencent.mm",listOf("Balance","CNY 1234.56"),0)))
        assertIs<RecognitionResult.NoMatch>(parser.parse(alipay().copy(texts=alipay().texts.filterNot { it=="Payment Successful" })))
        assertIs<RecognitionResult.NoMatch>(parser.parse(wechat().copy(texts=wechat().texts+listOf("Transaction Number","OTHER-0001"))))
    }
    @Test fun transactionOrderNumberAndYearFirstSlashDateMatchTheEnglishWeChatDetailVariant() {
        val page=wechat("2026/11/01 13:05:09").copy(texts=wechat("2026/11/01 13:05:09").texts.map { if(it=="Transaction ID:") "Transaction Order No." else it })
        val candidate=parsed(page)
        assertEquals("EN-WX-000001",candidate.externalId)
        assertEquals(parsed(wechat()).evidenceHash(),candidate.evidenceHash())
        assertNotEquals(candidate.reliableKey,parsed(page.copy(texts=page.texts.map { if(it=="EN-WX-000001") "EN-WX-000002" else it })).reliableKey)
    }
    @Test fun paidAwaitingReceiptSupportsMixedChineseAmountsAndEnglishPaymentFields() {
        for(status in listOf("Waiting confirmation of receipt","等待确认收货")) {
            val page=alipay("2026-11-01 13:05:09").copy(texts=alipay("2026-11-01 13:05:09").texts.map {
                when(it) { "Payment Successful" -> status; "Expense CNY 1,234.56" -> "支出1234.56元"; else -> it }
            })
            val result=parsed(page);assertEquals(123456L,result.amount.minor);assertEquals(TransactionType.EXPENSE,result.type)
            assertEquals(parsed(alipay()).occurredAtMs,result.occurredAtMs);assertNull(result.externalId)
        }
    }
    @Test fun awaitingReceiptDoesNotRecognizeUnpaidOrUnsettledIncomeAndStillRequiresPaymentTime() {
        val page=alipay().copy(texts=alipay().texts.map { if(it=="Payment Successful") "Waiting confirmation of receipt" else it })
        val income=page.copy(texts=page.texts.map { if(it=="Expense CNY 1,234.56") "Income CNY 1,234.56" else it })
        for(p in listOf(income,page.copy(texts=page.texts+"Unpaid"),page.copy(texts=page.texts.filterNot { it=="Payment Time" }),page.copy(texts=page.texts+"Refund")))
            assertIs<RecognitionResult.NoMatch>(parser.parse(p))
    }
    @Test fun awaitingReceiptWithoutExplicitDirectionIsSkippedWithoutThrowing() {
        val page=alipay().copy(texts=alipay().texts.map {
            when(it) { "Payment Successful" -> "Waiting confirmation of receipt"; "Expense CNY 1,234.56" -> "-CNY 1,234.56"; else -> it }
        })
        assertIs<RecognitionResult.NoMatch>(parser.parse(page))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page.copy(texts=page.texts.filterNot { it=="-CNY 1,234.56" })))
    }
}
