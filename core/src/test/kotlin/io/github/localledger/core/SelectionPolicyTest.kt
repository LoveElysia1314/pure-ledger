package io.github.localledger.core

import java.math.BigInteger
import kotlin.test.*

class SelectionPolicyTest {
    private val catalog = LedgerCatalog(listOf(LedgerBook(DEFAULT_BOOK, "日常"), LedgerBook("travel", "旅行")),
        listOf(LedgerCategory("food", TransactionType.EXPENSE, "餐饮"), LedgerCategory("salary", TransactionType.INCOME, "收入")),
        listOf(LedgerAccount("cash", "现金", BigInteger.ZERO, true), LedgerAccount("bank", "银行卡", BigInteger.ZERO, true),
            LedgerAccount("travel-cash", "现金", BigInteger.ZERO, false)))
    private val memory = SelectionMemory(DEFAULT_BOOK, listOf(
        RecentChoice(TransactionType.EXPENSE, EntryChoice(DEFAULT_BOOK, "food", "cash"), 1),
        RecentChoice(TransactionType.INCOME, EntryChoice(DEFAULT_BOOK, "salary", "bank"), 2)))
    @Test fun `displayed and current book take precedence over an old merchant book`() {
        val choice = SelectionPolicy.resolve(catalog, memory, TransactionType.EXPENSE, DEFAULT_BOOK, EntryChoice("travel", accountId = "travel-cash"))
        assertEquals(DEFAULT_BOOK, choice.bookId); assertEquals("travel-cash", choice.accountId)
        assertEquals("travel", SelectionPolicy.resolve(catalog, memory.copy(currentBookId = "travel"), TransactionType.EXPENSE).bookId)
    }
    @Test fun `designated fallback is used only when there is no usable current or merchant book`() {
        val fallback = memory.copy(currentBookId = null, defaultBookId = "travel")
        assertEquals("travel", SelectionPolicy.resolve(catalog, fallback, TransactionType.EXPENSE).bookId)
        assertEquals(DEFAULT_BOOK, SelectionPolicy.resolve(catalog, fallback.copy(currentBookId = DEFAULT_BOOK), TransactionType.EXPENSE).bookId)
        val withoutInitial = catalog.copy(books = listOf(LedgerBook("travel", "旅行")))
        assertEquals("travel", SelectionPolicy.resolve(withoutInitial, memory, TransactionType.EXPENSE).bookId)
    }
    @Test fun `ordinary categories are separated while most recent account is shared`() {
        val expense = SelectionPolicy.resolve(catalog, memory, TransactionType.EXPENSE)
        val income = SelectionPolicy.resolve(catalog, memory, TransactionType.INCOME)
        assertEquals("food", expense.categoryId); assertEquals("salary", income.categoryId)
        assertEquals("bank", expense.accountId); assertEquals("bank", income.accountId)
    }
    @Test fun `a valid displayed book overrides current while accounts stay global`() {
        val result = SelectionPolicy.resolve(catalog, memory, TransactionType.EXPENSE, "travel")
        assertEquals("travel", result.bookId); assertEquals("bank", result.accountId); assertEquals("food",result.categoryId)
    }
    @Test fun `removed books and cross book references are resolved to available values`() {
        val result = SelectionPolicy.resolve(catalog, memory.copy(currentBookId = "gone"), TransactionType.EXPENSE,
            merchant = EntryChoice("travel", "food", "cash"))
        assertEquals("travel", result.bookId); assertEquals("food",result.categoryId); assertEquals("cash", result.accountId)
        assertEquals(DEFAULT_BOOK, SelectionPolicy.resolve(catalog, memory.copy(currentBookId = "gone"), TransactionType.EXPENSE).bookId)
    }
    @Test fun `legacy unassigned accounts fall back to an available account`() {
        val result = SelectionPolicy.resolve(catalog, memory, TransactionType.EXPENSE, merchant = EntryChoice(DEFAULT_BOOK, affectsBalance = false))
        assertNull(result.categoryId); assertEquals("bank",result.accountId); assertFalse(result.affectsBalance)
    }
    @Test fun `wrong category direction never carries into an ordinary candidate`() {
        assertNull(SelectionPolicy.resolve(catalog, memory, TransactionType.EXPENSE, merchant = EntryChoice(DEFAULT_BOOK, "salary", "cash")).categoryId)
    }
    @Test fun `transfer remembers two accounts and cannot choose the same account twice`() {
        val transfer = RecentChoice(TransactionType.TRANSFER, EntryChoice(DEFAULT_BOOK, accountId = "cash", toAccountId = "bank"), 3)
        val result = SelectionPolicy.resolve(catalog, memory.copy(recent = memory.recent + transfer), TransactionType.TRANSFER)
        assertEquals("cash", result.accountId); assertEquals("bank", result.toAccountId); assertNull(result.categoryId)
        assertNull(SelectionPolicy.resolve(catalog, memory, TransactionType.TRANSFER, merchant = EntryChoice(DEFAULT_BOOK, accountId = "cash", toAccountId = "cash")).toAccountId)
        assertEquals("bank",SelectionPolicy.resolve(catalog, memory.copy(recent = memory.recent + transfer.copy(choice = transfer.choice.copy(accountId = null))), TransactionType.TRANSFER).accountId)
    }
    @Test fun `recognized app account precedes recent use while merchant choice has priority`() {
        val withWechat = catalog.copy(accounts = catalog.accounts + LedgerAccount("wechat", "微信", BigInteger.ZERO, false))
        assertEquals("wechat", SelectionPolicy.resolve(withWechat, memory, TransactionType.EXPENSE, appPackage = "com.tencent.mm").accountId)
        assertEquals("cash", SelectionPolicy.resolve(withWechat, memory, TransactionType.EXPENSE, merchant = EntryChoice(DEFAULT_BOOK, accountId = "cash"), appPackage = "com.tencent.mm").accountId)
        assertEquals("wechat", SelectionPolicy.resolve(withWechat, memory, TransactionType.EXPENSE, "travel", appPackage = "com.tencent.mm").accountId)
        assertEquals("bank", SelectionPolicy.resolve(withWechat, memory, TransactionType.EXPENSE, appPackage = "example.other").accountId)
    }
    @Test fun `deleted accounts cannot be recalled by application or merchant memory`() {
        val archived = catalog.copy(accounts = catalog.accounts.map { it.copy(archived = true) } + LedgerAccount("wx", "微信", BigInteger.ZERO, false, archived = true))
        val choice = SelectionPolicy.resolve(archived, memory, TransactionType.EXPENSE, merchant = EntryChoice(DEFAULT_BOOK, accountId = "wx"), appPackage = "com.tencent.mm")
        assertNull(choice.accountId)
        assertTrue(archived.activeAccounts.isEmpty())
        assertEquals(listOf("cash"), archived.entryAccounts("cash").map { it.id })
    }
    @Test fun `english payment account names are matched without renaming or merging accounts`() {
        val english = catalog.copy(accounts = listOf(LedgerAccount("wx", "WeChat", BigInteger.ZERO, false), LedgerAccount("ali", "Alipay", BigInteger.ZERO, false)))
        assertEquals("wx", SelectionPolicy.resolve(english, memory, TransactionType.EXPENSE, appPackage="com.tencent.mm").accountId)
        assertEquals("ali", SelectionPolicy.resolve(english, memory, TransactionType.EXPENSE, appPackage="com.eg.android.AlipayGphone").accountId)
        val ambiguous = english.copy(accounts = english.accounts + LedgerAccount("wx2", "微信", BigInteger.ZERO, false))
        assertEquals("wx",SelectionPolicy.resolve(ambiguous, memory.copy(recent=emptyList()), TransactionType.EXPENSE, appPackage="com.tencent.mm").accountId)
    }
}
