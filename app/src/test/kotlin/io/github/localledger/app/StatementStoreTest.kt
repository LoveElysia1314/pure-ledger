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
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatementStoreTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    @Before fun prepare() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase("synthetic-statement.db"); store = LedgerStore(context, "synthetic-statement.db") }
    @After fun close() { store.close(); context.deleteDatabase("synthetic-statement.db") }
    private fun date(day: String) = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Test fun amountAndDateBoundsMatchRowsStatisticsAndPaginationWithoutChangingBalances() {
        val account=store.saveAccount("合成现金",BigInteger("100000"))
        val day=date("2026-02-28")
        listOf(0L,100,100,101,200,201,Long.MAX_VALUE).forEachIndexed { index,value ->
            store.createEntry(EntryDraft(TransactionType.EXPENSE,Money(value),"样本$index",day+index,accountId=account))
        }
        store.createEntry(EntryDraft(TransactionType.INCOME,Money(150),"三月",date("2026-03-01"),accountId=account))
        val before=store.snapshot().catalog.accounts.single().balanceMinor
        val query=LedgerQuery(minimumMinor=100,maximumMinor=200).inDateRange(LocalDate.of(2026,2,28),LocalDate.of(2026,2,28))
        val snapshot=store.snapshot(1,query)
        assertEquals(4,snapshot.count); assertEquals(1,snapshot.rows.size)
        assertEquals(BigInteger("501"),snapshot.expense); assertEquals(BigInteger.ZERO,snapshot.income)
        assertEquals(snapshot.cashFlow,snapshot.days.single().totals)
        assertEquals(1,store.snapshot(query=LedgerQuery(maximumMinor=0)).count)
        assertEquals(1,store.snapshot(query=LedgerQuery(minimumMinor=Long.MAX_VALUE)).count)
        assertEquals(before,store.snapshot().catalog.accounts.single().balanceMinor)
    }
    @Test fun dailyAndMonthlyStatementsUseAllFilteredRowsBeforePagination() {
        val jan = date("2026-01-02")
        repeat(70) { store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(100), "合成", jan + it, affectsBalance = false)) }
        store.createEntry(EntryDraft(TransactionType.INCOME, Money(2000), "合成收入", jan + 100))
        val from = store.saveAccount("转出", null); val to = store.saveAccount("转入", null)
        store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(1000), "", jan + 101, accountId = from, toAccountId = to, fee = Money(10), discount = Money(5)))
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(500), "次日", date("2026-01-03")))
        val other = store.saveBook("其他账本")
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(50000), "其他", jan, bookId = other))
        val deleted = store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(900), "已删", jan)); store.deleteEntry(deleted, 0)
        val snapshot = store.snapshot(query = LedgerQuery(bookId = DEFAULT_BOOK))
        assertEquals(50, snapshot.rows.size); assertEquals(73, snapshot.count)
        assertEquals(BigInteger("7500"), snapshot.cashFlow.expense); assertEquals(BigInteger("-5505"), snapshot.cashFlow.net)
        assertEquals(72, snapshot.days.single { it.key == "2026-01-02" }.totals.count)
        assertEquals(BigInteger("7000"), snapshot.days.single { it.key == "2026-01-02" }.totals.expense)
        assertEquals(snapshot.cashFlow, snapshot.monthly.single().totals)
        assertEquals(73, store.snapshot(1000, LedgerQuery(bookId = DEFAULT_BOOK)).rows.size)
    }
    @Test fun monthBoundaryAndTypeFiltersProduceMatchingSummariesIncludingZeroEntries() {
        store.createEntry(EntryDraft(TransactionType.INCOME, Money(0), "一月", date("2026-01-31")))
        store.createEntry(EntryDraft(TransactionType.INCOME, Money(300), "二月", date("2026-02-01")))
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(900), "二月支出", date("2026-02-01")))
        val selected = store.snapshot(query = LedgerQuery(type = TransactionType.INCOME, fromMs = date("2026-01-01"), untilMs = date("2026-02-01")))
        assertEquals(1, selected.cashFlow.incomeCount); assertEquals(0, selected.cashFlow.expenseCount)
        assertEquals(listOf("2026-01"), selected.monthly.map { it.key }); assertEquals(selected.cashFlow, selected.days.single().totals)
        assertEquals(listOf("2026-02", "2026-01"), store.snapshot().monthly.map { it.key })
    }
    @Test fun transfersAppearInPeriodStatementsWithoutBecomingIncomeOrExpense() {
        val from = store.saveAccount("甲", null); val to = store.saveAccount("乙", null)
        store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(1000), "", date("2026-01-01"), accountId = from, toAccountId = to, discount = Money(50)))
        val flow = store.snapshot(query = LedgerQuery(type = TransactionType.TRANSFER)).monthly.single().totals
        assertEquals(0, flow.incomeCount); assertEquals(0, flow.expenseCount); assertEquals(1, flow.transferCount)
        assertEquals(BigInteger("1050"), flow.received); assertEquals(BigInteger("50"), flow.net)
    }
}
