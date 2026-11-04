package io.github.localledger.core

import kotlin.test.*

class CoreTest {
    private fun page(vararg extra: String) = PageSnapshot("com.tencent.mm", listOf(
        "账单详情", "商户名称", "测试商店", "支出金额", "￥12.30",
        "支付时间", "2026-10-10 12:00:00") + extra, 0)

    @Test fun cnyConversionIsExact() {
        assertEquals(123_456L, Money.parseCny("￥1,234.56").minor)
        assertEquals(-123L, Money.parseCny("-1.23元").minor)
        assertFailsWith<ArithmeticException> { Money.parseCny("1.001") }
        assertFailsWith<ArithmeticException> { Money.parseCny("999999999999999999999") }
        assertFailsWith<IllegalArgumentException> { Money.parseCny("1,23.45") }
        assertFailsWith<IllegalArgumentException> { Money.parseCny("余额12.30") }
    }

    @Test fun syntheticPaymentProducesCandidate() {
        val result = assertIs<RecognitionResult.Recognized>(DetailPageParser().parse(page("订单号", "TEST-001")))
        assertEquals(1230L, result.candidate.amount.minor)
        assertEquals("测试商店", result.candidate.merchant)
        assertEquals("com.tencent.mm:TEST-001", result.candidate.reliableKey)
    }

    @Test fun unrelatedOrAmbiguousPagesAreRejected() {
        val parser = DetailPageParser()
        assertIs<RecognitionResult.NoMatch>(parser.parse(PageSnapshot("com.tencent.mm", listOf("购物车", "￥12.30"), 0)))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(appPackage = "example.unrelated")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page("收入金额", "12.30")))
        assertIs<RecognitionResult.NoMatch>(parser.parse(page("支出金额", "20.00")))
    }

    @Test fun syntheticIncomeIsSupported() {
        val snapshot = PageSnapshot("com.eg.android.AlipayGphone", listOf("交易详情", "交易对方", "测试付款方",
            "收入金额", "88.00"), 0)
        assertEquals(TransactionType.INCOME,
            assertIs<RecognitionResult.Recognized>(DetailPageParser().parse(snapshot)).candidate.type)
    }

    @Test fun committedIdIsSuppressedButAnotherRealIdIsAccepted() {
        val c = assertIs<RecognitionResult.Recognized>(DetailPageParser().parse(page("订单号", "TEST-001"))).candidate
        val gate = CandidateGate()
        assertTrue(gate.shouldPresent(c, 0))
        assertFalse(gate.shouldPresent(c, 100))
        gate.rememberCommitted(c)
        assertFalse(gate.shouldPresent(c, 10_000))
        assertTrue(gate.shouldPresent(c.copy(externalId = "TEST-002"), 10_001))
    }

    @Test fun noIdIsMergedTemporarilyAndNotPermanentlyBlocked() {
        val c = assertIs<RecognitionResult.Recognized>(DetailPageParser().parse(page())).candidate
        val gate = CandidateGate(1000)
        assertTrue(gate.shouldPresent(c, 0))
        assertFalse(gate.shouldPresent(c, 500))
        gate.rememberCommitted(c)
        assertTrue(gate.shouldPresent(c, 1000))
        gate.cancel(c)
        assertTrue(gate.shouldPresent(c, 1001))
    }
}
