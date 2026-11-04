package io.github.localledger.app

import android.content.Context
import android.database.sqlite.SQLiteException
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.data.SaveResult
import io.github.localledger.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerStoreTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-ledger.db"
    private fun candidate(id: String = "synthetic-001", amount: Long = 1234) = TransactionCandidate(
        "com.tencent.mm", TransactionType.EXPENSE, Money(amount), "合成商户", id,
        "2026-10-31 12:00:00", "test-v1", 1793419200000)
    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(name)
        store = LedgerStore(context, name)
    }
    @After fun cleanup() { store.close(); context.deleteDatabase(name) }

    @Test fun saveAndReopenPreservesEditsReceiptsAndUnassignedAccount() {
        val source = candidate()
        val result = store.save(ConfirmedEntry(source, Money(1250), "核对后的商户", note = "合成备注"))
        assertTrue(result is SaveResult.Saved)
        store.close(); store = LedgerStore(context, name)
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(1250L, snapshot.rows.single().amount.minor)
        assertEquals("核对后的商户", snapshot.rows.single().merchant)
        assertEquals("合成备注", snapshot.rows.single().note)
        assertEquals(SaveResult.AlreadySaved, store.existing(source))
        store.readableDatabase.rawQuery("SELECT account_id FROM entries", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
        }
    }
    @Test fun duplicateCannotWriteAgainAndChangedSourceIsAConflict() {
        val source = candidate()
        store.save(ConfirmedEntry(source))
        assertEquals(SaveResult.AlreadySaved, store.save(ConfirmedEntry(source, Money(999))))
        assertEquals(SaveResult.Conflict, store.save(ConfirmedEntry(source.copy(amount = Money(1235)))))
        assertEquals(1, store.snapshot().count)
        assertEquals(1234L, store.snapshot().rows.single().amount.minor)
    }
    @Test fun similarPaymentsWithDifferentIdsRemainSeparate() {
        store.save(ConfirmedEntry(candidate("synthetic-001")))
        store.save(ConfirmedEntry(candidate("synthetic-002")))
        assertEquals(2, store.snapshot().count)
    }
    @Test fun receiptFailureRollsBackEntryAndAllowsRetry() {
        store.writableDatabase.execSQL("""CREATE TRIGGER fail_receipt BEFORE INSERT ON recognition_receipts
            BEGIN SELECT RAISE(ABORT,'synthetic fault'); END""")
        try { store.save(ConfirmedEntry(candidate())); fail("Expected database fault") } catch (_: SQLiteException) { }
        assertEquals(0, store.snapshot().count)
        assertNull(store.existing(candidate()))
        store.writableDatabase.execSQL("DROP TRIGGER fail_receipt")
        assertTrue(store.save(ConfirmedEntry(candidate())) is SaveResult.Saved)
    }
    @Test fun integerPrecisionAndTotalsBeyondLongArePreserved() {
        store.save(ConfirmedEntry(candidate("synthetic-001", Long.MAX_VALUE)))
        store.save(ConfirmedEntry(candidate("synthetic-002", Long.MAX_VALUE)))
        val snapshot = store.snapshot(1)
        assertEquals(1, snapshot.rows.size)
        assertEquals(2, snapshot.count)
        assertEquals(Long.MAX_VALUE, snapshot.rows.single().amount.minor)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, snapshot.expense)
    }
    @Test fun concurrentSameSourceHasOneCommittedEntry() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map { workers.submit<SaveResult> { store.save(ConfirmedEntry(candidate())) } }
            val results = tasks.map { it.get() }
            assertEquals(1, results.count { it is SaveResult.Saved })
            assertEquals(1, results.count { it == SaveResult.AlreadySaved })
            assertEquals(1, store.snapshot().count)
        } finally { workers.shutdownNow() }
    }
    @Test fun sourceReceiptCannotReferenceMissingEntry() {
        store.save(ConfirmedEntry(candidate()))
        try {
            store.writableDatabase.delete("entries", null, null)
            fail("Expected foreign key restriction")
        } catch (_: SQLiteException) { }
        assertEquals(1, store.snapshot().count)
    }
}
