package io.github.localledger.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class BookLifecycleTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-book-lifecycle.db"
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name) }

    @Test fun initialBookCanBeDeletedAndOnlyDeletingTheLastBookCreatesAReplacement() {
        val travel = store.saveBook("旅行", defaultBook = true)
        store.setCurrentBook(DEFAULT_BOOK)
        store.saveCategory( TransactionType.EXPENSE, "自建但未使用")
        val categories=store.snapshot().catalog.categories
        store.deleteCatalog("books", DEFAULT_BOOK)
        val remaining = store.snapshot()
        assertEquals(listOf(travel), remaining.catalog.books.map { it.id })
        assertEquals(travel, remaining.selection.currentBookId); assertEquals(travel, remaining.selection.defaultBookId)
        store.deleteCatalog("books", travel)
        val replacement = store.snapshot()
        assertEquals("默认账本", replacement.catalog.books.single().name)
        val newId = replacement.catalog.books.single().id
        assertNotEquals(DEFAULT_BOOK, newId); assertNotEquals(travel, newId)
        assertEquals(newId, replacement.selection.currentBookId); assertNull(replacement.selection.defaultBookId)
        assertEquals(categories,replacement.catalog.categories)
        store.close(); store = LedgerStore(context, name)
        assertEquals(newId, store.snapshot().catalog.books.single().id)
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
    }
    @Test fun replacementFailureRollsBackBookCatalogAndSelectionTogether() {
        store.setDefaultBook(DEFAULT_BOOK)
        val before = store.snapshot()
        store.writableDatabase.execSQL("CREATE TRIGGER fail_replacement BEFORE INSERT ON books BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { store.deleteCatalog("books", DEFAULT_BOOK); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.snapshot())
    }
    @Test fun referencedBookCannotSilentlyLoseMoneyAndDefaultFlagDoesNotBlockDeletion() {
        val other = store.saveBook("备用")
        store.setDefaultBook(other)
        store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(123), "合成商户", 1))
        val before = store.snapshot()
        try { store.deleteCatalog("books", DEFAULT_BOOK); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.snapshot())
        store.deleteCatalog("books", other)
        assertEquals(1, store.snapshot().count); assertNull(store.snapshot().selection.defaultBookId)
    }
    @Test fun regeneratedBookRoundTripsWithoutANameConflictOrAnExtraInitialBook() {
        store.deleteCatalog("books", DEFAULT_BOOK)
        val packet = LedgerExchange.decode(store.exportExchange())
        val target = "synthetic-book-lifecycle-target.db"
        context.deleteDatabase(target)
        try { LedgerStore(context, target).use {
            assertTrue(it.previewExchange(packet).conflicts.isEmpty())
            it.commitExchange(packet)
            assertEquals(packet.books, it.snapshot().catalog.books)
            assertEquals(0, it.commitExchange(packet).added)
        } } finally { context.deleteDatabase(target) }
    }
    @Test fun actualV5SelectionTableUpgradesWithoutChangingAnyFinancialRows() {
        store.close()
        object : SQLiteOpenHelper(context, name, null, 5) {
            override fun onCreate(db: SQLiteDatabase) {
                LedgerSchema.create(db)
                db.execSQL("DROP TABLE selection_state")
                db.execSQL("CREATE TABLE selection_state(id INTEGER PRIMARY KEY CHECK(id=1),book_id TEXT REFERENCES books(id) ON DELETE SET NULL)")
                db.execSQL("INSERT INTO selection_state VALUES(1,?)", arrayOf(DEFAULT_BOOK))
                db.execSQL("INSERT INTO books VALUES('trip','旅行')")
                db.execSQL("UPDATE selection_state SET book_id='trip'")
                db.execSQL("INSERT INTO entries VALUES(7,'kept-uid','EXPENSE',123,'合成商户',12345,'保留备注',100,200,'trip',NULL,NULL,NULL,0,0,0,'MANUAL','{}',4,0)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.use { it.writableDatabase }
        store = LedgerStore(context, name)
        val row = store.snapshot().rows.single()
        assertEquals(9, store.readableDatabase.version); assertEquals(7, row.id.toInt())
        assertEquals(123, row.amount.minor.toInt()); assertEquals("保留备注", row.note); assertEquals(4, row.revision.toInt())
        assertEquals("trip", store.snapshot().selection.currentBookId); assertNull(store.snapshot().selection.defaultBookId)
        store.setDefaultBook(DEFAULT_BOOK)
        store.close(); store = LedgerStore(context, name)
        assertEquals(DEFAULT_BOOK, store.snapshot().selection.defaultBookId)
        assertEquals(row, store.snapshot().rows.single())
    }
}
