package io.github.localledger.app

import io.github.localledger.app.data.*
import io.github.localledger.app.ui.trendPoints
import io.github.localledger.app.ui.entryTitle
import io.github.localledger.app.ui.compositionSlices
import io.github.localledger.core.*
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class StatisticsTrendTest {
    @Test fun categoryCompositionBoundsTheLegendAndRetainsEveryMinorUnitAndCount() {
        val huge=BigInteger.valueOf(Long.MAX_VALUE)*BigInteger.TEN
        val buckets=(1..12).map { SummaryBucket("Category $it",TransactionType.EXPENSE,huge+BigInteger.valueOf(it.toLong()),it) }+
            SummaryBucket("Salary",TransactionType.INCOME,huge*BigInteger.TEN,99)
        val slices=compositionSlices(buckets,TransactionType.EXPENSE)
        assertEquals(8,slices.size);assertNull(slices.last().name);assertEquals(5,slices.last().categories)
        assertEquals(12,slices.sumOf{it.categories});assertEquals(78,slices.sumOf{it.count})
        assertEquals(buckets.filter{it.type==TransactionType.EXPENSE}.fold(BigInteger.ZERO){a,b->a+b.amount},slices.fold(BigInteger.ZERO){a,b->a+b.amount})
        assertEquals("Category 12",slices.first().name)
        assertTrue(compositionSlices(emptyList(),TransactionType.EXPENSE).isEmpty())
    }
    @Test fun allResultStatementsPreserveExactMoneyAndZeroDaysIndependentlyOfVisiblePage() {
        val money=BigInteger.valueOf(Long.MAX_VALUE)*BigInteger.valueOf(2)
        val first=LocalDate.of(2026,1,1)
        val result=LedgerSnapshot(count=100,days=listOf(PeriodStatement(first.toString(),CashFlow(expense=money,expenseCount=100))),
            query=LedgerQuery().inDateRange(first,first.plusDays(2)))
        val points=trendPoints(result)
        assertEquals(3,points.size);assertEquals(money,points[0].totals.expense)
        assertEquals(100,points[0].totals.count);assertEquals(CashFlow(),points[1].totals)
        assertEquals(first.plusDays(2),points.last().last)
    }
    @Test fun longHistoryHasBoundedBucketsAndKeepsEveryMonthAndTransferCost() {
        val first=YearMonth.of(2000,1)
        val monthly=(0 until 180).map { PeriodStatement(first.plusMonths(it.toLong()).toString(),CashFlow(expense=BigInteger.TEN,transferCost=BigInteger.ONE,expenseCount=1)) }
        val result=LedgerSnapshot(days=listOf(PeriodStatement("2000-01-01",monthly.first().totals)),monthly=monthly,
            query=LedgerQuery().inDateRange(first.atDay(1),first.plusMonths(179).atEndOfMonth()))
        val points=trendPoints(result)
        assertTrue(points.size<=60)
        assertEquals(BigInteger("1800"),points.fold(BigInteger.ZERO){a,b->a+b.totals.expense})
        assertEquals(BigInteger("180"),points.fold(BigInteger.ZERO){a,b->a+b.totals.transferCost})
        assertEquals(180,points.sumOf { it.totals.count })
    }
    @Test fun noteHasReadingPriorityAndCategoryNeverReplacesAnExistingNote() {
        val row=LedgerRow(1,TransactionType.EXPENSE,Money(123),"Merchant",0,"Lunch note")
        assertEquals("Lunch note",entryTitle(row,LedgerCatalog()))
        assertEquals("Merchant",entryTitle(row.copy(note=""),LedgerCatalog()))
    }
}
