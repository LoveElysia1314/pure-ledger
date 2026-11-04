package io.github.localledger.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import io.github.localledger.core.migration.LegacyWorkbook
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class GlobalAccountsAndMergeTest {
    private lateinit var store: LedgerStore
    private lateinit var context: Context
    private val name = "synthetic-global-account-merge.db"
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name); context.deleteDatabase("synthetic-global-copy.db") }
    private fun packet() = LedgerExchange.decode(store.exportExchange())
    private fun foreignKeys() { store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) } }

    @Test fun oneAccountWorksAcrossBooksAndCalibratesItsGlobalBalance() {
        val other = store.saveBook("旅行")
        val account = store.saveAccount("现金", BigInteger("10000"))
        val bank = store.saveAccount("银行卡", BigInteger.ZERO)
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(123), "合成甲", 1, accountId = account))
        store.createEntry(EntryDraft(TransactionType.INCOME, Money(456), "合成乙", 2, bookId = other, accountId = account))
        store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(100), "", 3, bookId = other, accountId = account, toAccountId = bank, fee = Money(3)))
        assertEquals(BigInteger("10233"), store.snapshot().catalog.accounts.single { it.id == account }.balanceMinor)
        assertEquals(2, store.snapshot(query = LedgerQuery(bookId = other, accountId = account)).count)
        assertEquals(3, store.snapshot(query = LedgerQuery(accountId = account)).count)
        store.saveAccount("现金", BigInteger("20000"), account)
        assertEquals(BigInteger("20000"), store.snapshot().catalog.accounts.single { it.id == account }.balanceMinor)
        val data = packet(); assertEquals(5, data.schemaVersion)
        LedgerStore(context, "synthetic-global-copy.db").use { copy ->
            copy.commitExchange(data); assertEquals(store.snapshot().catalog.accounts, copy.snapshot().catalog.accounts)
            assertEquals(0, copy.commitExchange(data).added)
        }
        foreignKeys()
    }

    @Test fun deletingAnEmptyBookDoesNotDeleteOrRequireMovingAnAccount() {
        val book = store.saveBook("空账本", defaultBook = true)
        val account = store.saveAccount("钱包", BigInteger("12345"))
        store.setCurrentBook(book)
        store.deleteCatalog("books", book)
        assertEquals(account, store.snapshot().catalog.accounts.single().id)
        assertEquals(BigInteger("12345"), store.snapshot().catalog.accounts.single().balanceMinor)
        assertNull(store.snapshot().selection.defaultBookId)
        foreignKeys()
    }

    @Test fun deletedBillMarkersNoLongerBlockBookDeletionAndReceiptsSurvive() {
        val other = store.saveBook("保留")
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(123), "合成商户", "SYNTHETIC-DELETE-0001", null, "test", 1000)
        store.saveCandidate(ConfirmedEntry(source), "request-delete")
        val row = store.snapshot().rows.single(); store.deleteEntry(row.id, row.revision)
        val before = packet()
        store.setDefaultBook(DEFAULT_BOOK); store.deleteCatalog("books", DEFAULT_BOOK)
        val after = packet()
        assertEquals(listOf(other), after.books.map { it.id }); assertEquals(0, store.snapshot().count)
        assertEquals(before.recognition, after.recognition); assertEquals(before.entries.single().uid, after.entries.single().uid)
        assertTrue(after.entries.single().deleted); assertEquals(other, after.entries.single().bookId)
        assertTrue(store.prepareCandidate(source).repeated); assertNull(store.snapshot().selection.defaultBookId)
        foreignKeys()
    }

    @Test fun deletingTheLastBookWithTombstonesCreatesOneEmptyReplacement() {
        val id = store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1), "合成商户", 1), "deleted-uid")
        val row = store.entry(id)!!; store.deleteEntry(id, row.revision)
        store.deleteCatalog("books", DEFAULT_BOOK)
        val data = packet()
        assertEquals(1, data.books.size); assertNotEquals(DEFAULT_BOOK, data.books.single().id)
        assertEquals("默认账本", data.books.single().name); assertEquals(0, store.snapshot().count)
        assertEquals("deleted-uid", data.entries.single().uid); assertTrue(data.entries.single().deleted)
        foreignKeys()
    }

    @Test fun mergeKeepsMoneyBalancesIdsReceiptsAndTargetCategoryStyle() {
        val target = store.saveBook("保留")
        val account = store.saveAccount("现金", BigInteger("100000"))
        val bank = store.saveAccount("银行卡", BigInteger.ZERO)
        val sourceCategory = store.saveCategory( TransactionType.EXPENSE, "合成分类", icon = "cup", hidden = true)
        val targetCategory = store.saveCategory( TransactionType.EXPENSE, "合成分类", icon = "gift")
        val onlySource = store.saveCategory( TransactionType.INCOME, "合成收入", icon = "wallet")
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(123), "合成商户", "SYNTHETIC-MERGE-1", null, "test", 1000)
        store.saveCandidate(ConfirmedEntry(source, categoryId = sourceCategory, accountId = account), "request-merge")
        store.createEntry(EntryDraft(TransactionType.INCOME, Money(456), "合成收入方", 2, categoryId = onlySource, accountId = account))
        store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(100), "", 3, accountId = account, toAccountId = bank, fee = Money(3)))
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(99), "合成原有账", 4, bookId = target, accountId = bank))
        val imported = LegacyWorkbook.parse(workbookFixture(listOf("2.34")))
        store.commitImport(imported)
        val documentBook = store.snapshot().rows.first { it.source == "IMPORT" }.bookId
        store.mergeBooks(store.previewBookMerge(documentBook, DEFAULT_BOOK))
        val deleted = store.snapshot().rows.first { it.source == "IMPORT" }; store.deleteEntry(deleted.id, deleted.revision)
        store.setCurrentBook(DEFAULT_BOOK); store.setDefaultBook(DEFAULT_BOOK)
        val before = packet(); val balances = store.snapshot().catalog.accounts
        val plan = store.previewBookMerge(DEFAULT_BOOK, target)
        assertEquals(3, plan.count); assertEquals(1, plan.targetCount); assertEquals(BigInteger("123"), plan.expense)
        store.mergeBooks(plan)
        val after = packet()
        assertEquals(listOf(target), after.books.map { it.id }); assertEquals(4, store.snapshot().count)
        assertEquals(balances, store.snapshot().catalog.accounts)
        assertEquals(before.recognition, after.recognition); assertEquals(before.imports, after.imports)
        before.entries.forEach { old ->
            val current = after.entries.single { it.uid == old.uid }
            assertEquals(old.amount, current.amount); assertEquals(old.rawSource, current.rawSource); assertEquals(old.accountId, current.accountId)
            assertEquals(old.toAccountId, current.toAccountId); assertEquals(old.fee, current.fee); assertEquals(old.discount, current.discount)
            assertEquals(old.deleted, current.deleted); assertEquals(old.occurredAtMs, current.occurredAtMs)
        }
        assertEquals(sourceCategory,targetCategory);assertEquals(before.categories,after.categories)
        val style = after.categories.single { it.id == targetCategory }; assertEquals("cup", style.icon); assertTrue(style.hidden)
        assertEquals(targetCategory, after.entries.single { it.uid == "request-merge" }.categoryId)
        assertTrue(after.categories.any { it.id == onlySource })
        assertEquals(target, store.snapshot().selection.currentBookId); assertEquals(target, store.snapshot().selection.defaultBookId)
        assertTrue(store.prepareCandidate(source).repeated); assertEquals(0, store.commitImport(imported).added)
        foreignKeys()
    }

    @Test fun changedPreviewAndMidMergeFailureLeaveEverythingUnchanged() {
        val target = store.saveBook("目标")
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1), "合成一", 1))
        val stale = store.previewBookMerge(DEFAULT_BOOK, target)
        store.createEntry(EntryDraft(TransactionType.INCOME, Money(2), "合成二", 2))
        val before = packet()
        try { store.mergeBooks(stale); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, packet())
        val plan = store.previewBookMerge(DEFAULT_BOOK, target)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_merge BEFORE DELETE ON books BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        try { store.mergeBooks(plan); fail() } catch (_: android.database.SQLException) { }
        assertEquals(before, packet()); foreignKeys()
    }

    private fun legacyV6(broken: Boolean = false) {
        store.close(); context.deleteDatabase(name)
        object : SQLiteOpenHelper(context, name, null, 6) {
            override fun onCreate(db: SQLiteDatabase) {
                LedgerSchema.catalog(db, legacyAccounts = true); LedgerSchema.entries(db, "entries")
                LedgerSchema.receipts(db, "recognition_receipts", "entries"); LedgerSchema.imports(db); SelectionSchema.create(db)
                db.execSQL("INSERT INTO books VALUES('trip','旅行')")
                db.execSQL("INSERT INTO accounts VALUES('a',?,'现金','10000',1)", arrayOf(DEFAULT_BOOK))
                db.execSQL("INSERT INTO accounts VALUES('b','trip','现金','20000',0)")
                db.execSQL("INSERT INTO entries VALUES(1,'legacy-a','EXPENSE',123,'合成旧账',1000,'备注',100,200,?,NULL,?,NULL,0,0,1,'MANUAL','{}',4,0)", arrayOf(DEFAULT_BOOK, if(broken) "missing" else "a"))
                db.execSQL("INSERT INTO entries VALUES(2,'legacy-b','INCOME',456,'合成旧账',2000,'备注',300,400,'trip',NULL,'b',NULL,0,0,1,'MANUAL','{}',5,0)")
                db.execSQL("INSERT INTO selection_recent VALUES(?,'EXPENSE',NULL,'a',NULL,1,7)", arrayOf(DEFAULT_BOOK))
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.use { it.writableDatabase }
        store = LedgerStore(context, name)
    }

    @Test fun actualV6UpgradeSeparatesDuplicateNamesWithoutMergingBalancesOrChangingRows() {
        legacyV6()
        val rows = store.snapshot().rows
        assertEquals(9, store.readableDatabase.version)
        assertEquals(setOf("a", "b"), store.snapshot().catalog.accounts.map { it.id }.toSet())
        assertEquals(setOf("现金 · 默认账本", "现金 · 旅行"), store.snapshot().catalog.accounts.map { it.name }.toSet())
        assertEquals(BigInteger("9877"), store.snapshot().catalog.accounts.single { it.id == "a" }.balanceMinor)
        assertEquals(BigInteger("20456"), store.snapshot().catalog.accounts.single { it.id == "b" }.balanceMinor)
        assertEquals(4L, rows.single { it.id == 1L }.revision); assertEquals(200L, rows.single { it.id == 1L }.updatedAtMs)
        assertEquals("legacy-b", rows.single { it.id == 2L }.uid)
        assertEquals("a", store.snapshot().selection.recent.single().choice.accountId)
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(1), "新账", 3, bookId = "trip", accountId = "a"))
        assertEquals(BigInteger("9876"), store.snapshot().catalog.accounts.single { it.id == "a" }.balanceMinor)
        foreignKeys()
    }

    @Test fun oldV2ExchangeUsesTheSameAccountConversionAsDatabaseUpgrade() {
        legacyV6()
        val original = packet()
        val members = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(store.exportExchange().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; members[entry.name] = zip.readBytes() }
        }
        val records = org.json.JSONObject(members.getValue("records.json").toString(Charsets.UTF_8))
        val accounts = records.getJSONArray("accounts")
        repeat(accounts.length()) { index -> accounts.getJSONObject(index).apply {
            remove("archived"); put("name", "现金"); put("bookId", if (getString("id") == "a") DEFAULT_BOOK else "trip")
        } }
        val categories = records.getJSONArray("categories")
        repeat(categories.length()) { categories.getJSONObject(it).apply { remove("presetKey");put("bookId",DEFAULT_BOOK) } }
        val content = records.toString().toByteArray(Charsets.UTF_8)
        members["records.json"] = content
        members["manifest.json"] = org.json.JSONObject(members.getValue("manifest.json").toString(Charsets.UTF_8))
            .put("schemaVersion", 2).put("bytes", content.size).put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) })
            .toString().toByteArray(Charsets.UTF_8)
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip -> members.forEach { (name, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        val old = LedgerExchange.decode(output.toByteArray())
        assertEquals(original.accounts, old.accounts); assertTrue(store.previewExchange(old).conflicts.isEmpty())
        assertEquals(0, store.commitExchange(old).added); assertEquals(original.entries, packet().entries)
    }

    @Test fun maximumLengthLegacyNamesStayDistinctAndRemainExportable() {
        val books = listOf("a", "b", "c").map { LedgerBook(it, "同".repeat(16) + it) }
        val accounts = books.mapIndexed { index, book -> LedgerAccount(book.id, "账".repeat(100), BigInteger.valueOf(index.toLong()), true) to book.id }
        val converted = GlobalAccounts.legacyNames(accounts, books)
        assertEquals(3, converted.map { it.name }.distinct().size)
        assertTrue(converted.all { it.name.length <= 100 })
        assertEquals(accounts.map { it.first.id to it.first.openingMinor }, converted.map { it.id to it.openingMinor })
        converted.forEach { store.saveAccount(it.name, it.openingMinor, null) }
        assertEquals(3, packet().accounts.size)
    }

    @Test fun invalidV6ReferencesRollBackTheEntireSchemaUpgrade() {
        legacyV6(broken = true)
        try { store.snapshot(); fail() } catch (_: IllegalStateException) { }
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(6, db.version)
            db.rawQuery("SELECT book_id,name,opening_minor FROM accounts WHERE id='a'", null).use { assertTrue(it.moveToFirst()); assertEquals(DEFAULT_BOOK, it.getString(0)); assertEquals("10000", it.getString(2)) }
            db.rawQuery("SELECT COUNT(*) FROM entries", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        }
    }
}
