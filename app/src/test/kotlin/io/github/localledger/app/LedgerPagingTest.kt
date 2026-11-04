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
class LedgerPagingTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val database = "synthetic-paging.db"
    @Before fun open() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(database); store = LedgerStore(context, database) }
    @After fun close() { store.close(); context.deleteDatabase(database) }

    @Test fun pagesBeyondTheOldSqliteVariableLimitKeepExactSourcesOrderAndWholeScopeBalances() {
        val cash = store.saveAccount("合成现金", BigInteger.ZERO)
        val sources = mutableMapOf<String, String>()
        val drafts = List(1007) { index ->
            EntryDraft(if (index % 3 == 0) TransactionType.INCOME else TransactionType.EXPENSE,
                Money(if (index < 2) Long.MAX_VALUE else index.toLong()), "合成商户$index", 1_000_000L + index,
                note = "保留备注$index", accountId = cash)
        }
        store.transaction { db -> drafts.forEachIndexed { index, draft ->
            val source = "{\"row\":$index,\"unknown\":null,\"text\":\"${"x".repeat(4096)}\",\"escaped\":\"a\\nb\"}"
            sources["source-$index"] = source
            store.insertDraft(db, draft, uid = "source-$index", source = "IMPORT", raw = source, createdAtMs = index.toLong(), updatedAtMs = index.toLong())
        } }
        val expected = CashFlow.summarize(drafts.asSequence())
        val balance = drafts.fold(BigInteger.ZERO) { sum, draft -> sum + draft.accountEffects().getValue(cash) }
        listOf(1, 50, 950, 2000).forEach { limit ->
            val snapshot = store.snapshot(limit)
            assertEquals(1007, snapshot.count); assertEquals(minOf(1007, limit), snapshot.rows.size)
            assertEquals(expected, snapshot.cashFlow); assertEquals(balance, snapshot.catalog.accounts.single().balanceMinor)
            assertEquals((1006 downTo (1007 - snapshot.rows.size)).map { "source-$it" }, snapshot.rows.map { it.uid })
            snapshot.rows.forEach { row ->
                val index = row.uid.substringAfter('-').toInt()
                assertEquals(drafts[index], row.draft()); assertEquals(sources.getValue(row.uid), row.rawSource)
                assertEquals(index.toLong(), row.createdAtMs); assertEquals(index.toLong(), row.updatedAtMs)
            }
        }
        val filtered = store.snapshot(950, LedgerQuery(text = "商户1", minimumMinor = 100, maximumMinor = 999))
        assertEquals(drafts.count { it.merchant.contains("商户1") && it.amount.minor in 100..999 }, filtered.count)
        assertEquals(balance, filtered.catalog.accounts.single().balanceMinor)
        filtered.rows.forEach { assertEquals(sources.getValue(it.uid), it.rawSource) }
    }

    @Test fun requestLookupKeepsConflictAndTombstoneRulesAndBindsSpecialCharacters() {
        val draft = EntryDraft(TransactionType.EXPENSE, Money(123), "合成商户", 123)
        val uid = "request-' OR 1=1--"
        val first = store.createEntry(draft, uid)
        assertEquals(first, store.createEntry(draft, uid))
        assertNotEquals(first, store.createEntry(draft, "different"))
        try { store.createEntry(draft.copy(amount = Money(124)), uid); fail() } catch (_: IllegalArgumentException) { }
        store.deleteEntry(first, 0)
        try { store.createEntry(draft, uid); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.snapshot().count)
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(123), "合成商户", "source", null, "v1", 123)
        val confirmed = ConfirmedEntry(source)
        val recognized = store.saveCandidate(confirmed, "recognized-'?") as SaveResult.Saved
        assertEquals(recognized, store.saveCandidate(confirmed, "recognized-'?"))
        store.deleteEntry(recognized.id, 0)
        try { store.saveCandidate(confirmed, "recognized-'?"); fail() } catch (_: IllegalArgumentException) { }
        store.saveCandidate(confirmed, "recognized-next")
        val next = store.snapshot().rows.single { it.uid == "recognized-next" }
        assertEquals("recognized-'?", org.json.JSONObject(next.rawSource).getString("duplicate_of_uid"))
    }
}
