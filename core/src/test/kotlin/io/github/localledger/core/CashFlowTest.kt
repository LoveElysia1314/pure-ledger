package io.github.localledger.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigInteger

class CashFlowTest {
    @Test fun transferPrincipalIsSeparateFromIncomeAndExpenses() {
        val flow = CashFlow.summarize(sequenceOf(
            EntryDraft(TransactionType.EXPENSE, Money(1200), "", 1),
            EntryDraft(TransactionType.INCOME, Money(5000), "", 1),
            EntryDraft(TransactionType.TRANSFER, Money(10000), "", 1, accountId = "a", toAccountId = "b", fee = Money(30), discount = Money(10))))
        assertEquals(BigInteger("1200"), flow.expense); assertEquals(BigInteger("5000"), flow.income)
        assertEquals(BigInteger("10000"), flow.transfer); assertEquals(BigInteger("20"), flow.transferCost)
        assertEquals(BigInteger("9980"), flow.received); assertEquals(BigInteger("3780"), flow.net)
        assertEquals(3, flow.count)
    }
    @Test fun zeroAmountEntriesAndUnpostedHistoricalEntriesRemainInStatements() {
        val flow = CashFlow.summarize(sequenceOf(
            EntryDraft(TransactionType.EXPENSE, Money(0), "", 1, affectsBalance = false),
            EntryDraft(TransactionType.INCOME, Money(0), "", 1, affectsBalance = false),
            EntryDraft(TransactionType.EXPENSE, Money(500), "", 1, affectsBalance = false)))
        assertEquals(2, flow.expenseCount); assertEquals(1, flow.incomeCount)
        assertEquals(BigInteger("500"), flow.expense); assertEquals(BigInteger("-500"), flow.net)
        assertEquals(CashFlow(), CashFlow.summarize(emptySequence()))
    }
    @Test fun largeAmountsAndDiscountsDoNotOverflowLong() {
        val max = BigInteger.valueOf(Long.MAX_VALUE)
        val flow = CashFlow.summarize(sequenceOf(
            EntryDraft(TransactionType.EXPENSE, Money(Long.MAX_VALUE), "", 1),
            EntryDraft(TransactionType.EXPENSE, Money(Long.MAX_VALUE), "", 2),
            EntryDraft(TransactionType.TRANSFER, Money(Long.MAX_VALUE), "", 3, accountId = "a", toAccountId = "b", discount = Money(Long.MAX_VALUE))))
        assertEquals(max * BigInteger.TWO, flow.expense); assertEquals(max * BigInteger.TWO, flow.received)
        assertEquals(-max, flow.net); assertEquals(-max, flow.transferCost)
    }
}
