package io.github.localledger.app

import android.content.Context
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.data.setCurrentBook
import io.github.localledger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var graph: LedgerGraph
    private val name = "synthetic-flow.db"
    private val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1800),
        "合成商户", "synthetic-001", "2026-10-31 12:00:00", "test-v1", 1793419200000)
    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(name)
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().clear().commit()
        graph = LedgerGraph(context, LedgerStore(context, name), dispatcher)
        graph.setQuery(LedgerQuery())
    }
    @After fun cleanup() { graph.close(); context.deleteDatabase(name); Dispatchers.resetMain() }
    @Test fun coldProcessRestoresTheCurrentBookAndStartsAtThisMonthInsteadOfAnOldFilter() = runTest(dispatcher) {
        graph.close()
        val book = LedgerStore(context, name).use { store -> store.saveBook("旅行").also { store.setCurrentBook(it) } }
        graph = LedgerGraph(context, LedgerStore(context, name), dispatcher)
        advanceUntilIdle()
        val expected = LedgerQuery(bookId = book).inMonth(java.time.YearMonth.now())
        assertEquals(expected, graph.query.value)
        graph.setQuery(expected.inMonth(java.time.YearMonth.of(2020, 2))); advanceUntilIdle()
        graph.close(); graph = LedgerGraph(context, LedgerStore(context, name), dispatcher)
        advanceUntilIdle(); assertEquals(expected, graph.query.value)
    }
    @Test fun cancelNeverSavesAndObsoleteConfirmationCannotWrite() = runTest(dispatcher) {
        graph.enable(true)
        val token = graph.present(source)!!
        graph.discard(token)
        graph.confirm(token, ConfirmedEntry(source))
        advanceUntilIdle()
        assertNull(graph.pending.value)
        assertEquals(0, graph.snapshot.value.count)
    }
    @Test fun repeatedSaveClickCommitsOneEntryAndPublishesList() = runTest(dispatcher) {
        graph.enable(true)
        val token = graph.present(source)!!
        graph.confirm(token, ConfirmedEntry(source))
        graph.confirm(token, ConfirmedEntry(source))
        advanceUntilIdle()
        assertNull(graph.pending.value)
        assertEquals(1, graph.snapshot.value.count)
    }
    @Test fun replacedCandidateInvalidatesOldTokenAndDisableDiscardsPending() = runTest(dispatcher) {
        graph.enable(true)
        val old = graph.present(source)!!
        graph.present(source.copy(externalId = "synthetic-002"))
        graph.confirm(old, ConfirmedEntry(source))
        graph.enable(false)
        advanceUntilIdle()
        assertNull(graph.pending.value)
        assertEquals(0, graph.snapshot.value.count)
        assertNull(graph.present(source))
    }
    @Test fun sourceConflictCanBeSavedAgainWithoutOverwritingTheOriginal() = runTest(dispatcher) {
        graph.enable(true)
        val token = graph.present(source)!!
        graph.confirm(token, ConfirmedEntry(source)); advanceUntilIdle()
        val conflict = source.copy(amount = Money(1900))
        val second = graph.present(conflict)!!
        advanceUntilIdle()
        assertTrue(graph.pending.value!!.preparation!!.repeated)
        graph.confirm(second, ConfirmedEntry(conflict)); advanceUntilIdle()
        assertNull(graph.pending.value)
        assertEquals(listOf(1800L, 1900L), graph.snapshot.value.rows.map { it.amount.minor }.sorted())
    }
    @Test fun rapidBookSwitchesRememberTheLatestSpecificBookWhenReturningToAllBooks() = runTest(dispatcher) {
        val travel = LedgerStore(context, name).use { it.saveBook("旅行") }
        graph.setQuery(LedgerQuery(bookId = DEFAULT_BOOK)); graph.setQuery(LedgerQuery(bookId = travel)); graph.setQuery(LedgerQuery())
        advanceUntilIdle()
        assertNull(graph.query.value.bookId)
        assertEquals(travel, graph.snapshot.value.selection.currentBookId)
    }
}
