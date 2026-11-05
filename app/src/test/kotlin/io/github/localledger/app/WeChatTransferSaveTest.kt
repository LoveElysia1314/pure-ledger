package io.github.localledger.app

import android.content.Context
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeChatTransferSaveTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-wechat-income.db"
    private val copyName = "synthetic-wechat-income-copy.db"
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(name); context.deleteDatabase(copyName)
        store = LedgerStore(context, name)
    }
    @After fun close() { store.close(); context.deleteDatabase(name); context.deleteDatabase(copyName) }
    private fun source(english: Boolean = false, id: String = "SYNTHETIC-INCOME-0001"): TransactionCandidate {
        val texts = if (english) listOf("转账-来自合成付款方", "+42.80", "Current Status", "Deposited into Balance",
            "Transfer Time", "2026/11/3 08:00:00", "Received Time", "2026/11/4 09:08:07", "Transfer Order No.", id)
        else listOf("转账-来自合成付款方", "+42.80", "当前状态", "已存入零钱", "转账时间", "2026年11月3日 08:00:00",
            "收款时间", "2026年11月4日 09:08:07", "转账单号", id)
        return (PaymentDetailParser().parse(PageSnapshot("com.tencent.mm", texts, 0)) as RecognitionResult.Recognized).candidate
    }

    @Test fun switchingLanguageMarksTheSameIncomeAndRetryDoesNotCreditTheBalanceTwice() {
        val account = store.saveAccount("微信", BigInteger("10000"))
        assertFalse(store.prepareCandidate(source()).repeated)
        val saved = store.saveCandidate(ConfirmedEntry(source(), accountId = account), "synthetic-request-1")
        assertTrue(store.prepareCandidate(source(true)).repeated)
        assertEquals(SaveResult.AlreadySaved, store.existing(source(true)))
        assertEquals(saved, store.saveCandidate(ConfirmedEntry(source(true), accountId = account), "synthetic-request-1"))
        assertEquals(1, store.snapshot().count)
        assertEquals(BigInteger("14280"), store.snapshot().catalog.accounts.single().balanceMinor)
        assertEquals(source().occurredAtMs, store.snapshot().rows.single().occurredAtMs)
        assertEquals(TransactionType.INCOME, store.snapshot().rows.single().type)
    }

    @Test fun similarTransfersWithDifferentNumbersCreditSeparateConfirmedIncomes() {
        val account = store.saveAccount("微信", BigInteger("10000"))
        store.saveCandidate(ConfirmedEntry(source(), accountId = account), "synthetic-request-1")
        val second = source(id = "SYNTHETIC-INCOME-0002")
        assertFalse(store.prepareCandidate(second).repeated)
        store.saveCandidate(ConfirmedEntry(second, accountId = account), "synthetic-request-2")
        assertEquals(2, store.snapshot().count)
        assertEquals(BigInteger("18560"), store.snapshot().catalog.accounts.single().balanceMinor)
    }

    @Test fun exportedIncomeRetainsItsReceiptAndLanguageIndependentDuplicateEvidence() {
        val account = store.saveAccount("微信", BigInteger("10000"))
        store.saveCandidate(ConfirmedEntry(source(), accountId = account), "synthetic-request-1")
        val data = LedgerExchange.decode(store.exportExchange())
        assertEquals(1, data.recognition.size)
        LedgerStore(context, copyName).use { copy ->
            assertEquals(1, copy.commitExchange(data).added)
            assertTrue(copy.prepareCandidate(source(true)).repeated)
            assertEquals(source().occurredAtMs, copy.snapshot().rows.single().occurredAtMs)
            assertEquals(BigInteger("14280"), copy.snapshot().catalog.accounts.single().balanceMinor)
            assertEquals(0, copy.commitExchange(data).added)
        }
    }
}
