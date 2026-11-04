package io.github.localledger.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.data.prepareCandidate
import io.github.localledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.time.YearMonth

/** Opt-in capacity measurement, synthetic database only; never a phone benchmark. */
@RunWith(AndroidJUnit4::class)
class NativeLedgerCapacityTest {
    @Test fun tenThousandRowsRetainExactTotalsAndExposeStorageCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        check(avd == "LocalLedger_API35") { "Requires the isolated LocalLedger_API35 AVD." }
        val context = instrumentation.targetContext
        val name = "capacity-${System.nanoTime()}.db"
        val report = JSONObject().put("avd", avd).put("api", android.os.Build.VERSION.SDK_INT).put("rows", 10_000)
            .put("raw_bytes_per_row", 4096).put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val times = JSONObject()
        fun measure(label: String, action: () -> Unit) {
            val samples = List(3) { val start = System.nanoTime(); action(); (System.nanoTime() - start) / 1_000_000.0 }
            times.put(label, JSONObject().put("milliseconds", JSONArray(samples)).put("median_ms", samples.sorted()[1]))
        }
        try {
            LedgerStore(context, name).use { store ->
                val account = store.saveAccount("合成现金", BigInteger("100000000"))
                val destination = store.saveAccount("合成储蓄", BigInteger.ZERO)
                val now = System.currentTimeMillis()
                val drafts = List(10_000) { i ->
                    val type = if (i % 20 == 0) TransactionType.TRANSFER else if (i % 5 == 0) TransactionType.INCOME else TransactionType.EXPENSE
                    EntryDraft(type, Money(100L + i % 1000), "合成商户${i % 25}", now - i * 3_600_000L,
                        note = "合成容量测试", accountId = account, toAccountId = if (type == TransactionType.TRANSFER) destination else null,
                        fee = Money(if (type == TransactionType.TRANSFER) 1 else 0))
                }
                val raw = JSONObject().put("synthetic", "x".repeat(4096)).toString()
                store.transaction { db -> drafts.forEachIndexed { index, draft -> store.insertDraft(db, draft, uid = "capacity-$index", source = "IMPORT", raw = raw) } }
                val expected = CashFlow.summarize(drafts.asSequence())
                val balances = mutableMapOf<String, BigInteger>()
                drafts.forEach { it.accountEffects().forEach { (id, amount) -> balances[id] = balances.getOrDefault(id, BigInteger.ZERO) + amount } }
                measure("snapshot_all_first_page") {
                    val snapshot = store.snapshot(50)
                    assertEquals(10_000, snapshot.count); assertEquals(50, snapshot.rows.size); assertEquals(expected, snapshot.cashFlow)
                    assertEquals(BigInteger("100000000") + balances.getValue(account), snapshot.catalog.accounts.single { it.id == account }.balanceMinor)
                }
                measure("snapshot_current_month") {
                    val query = LedgerQuery(bookId = DEFAULT_BOOK).inMonth(YearMonth.now())
                    val snapshot = store.snapshot(50, query)
                    assertEquals(drafts.count { it.occurredAtMs >= query.fromMs!! && it.occurredAtMs < query.untilMs!! }, snapshot.count)
                }
                val candidate = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(120), "合成商户", "capacity-candidate", null, "capacity-v1", now)
                measure("prepare_candidate") { assertEquals(DEFAULT_BOOK, store.prepareCandidate(candidate).draft.bookId) }
                val next = EntryDraft(TransactionType.EXPENSE, Money(120), "合成新增", now, accountId = account)
                val id = store.createEntry(next, "capacity-new")
                measure("idempotent_save") { assertEquals(id, store.createEntry(next, "capacity-new")) }
                assertEquals(10_001, store.snapshot().count)
                report.put("database_bytes", context.getDatabasePath(name).length()).put("timings", times).put("exact_totals", true)
            }
            File(context.filesDir, "capacity-benchmark.json").writeText(report.toString(2))
        } finally { context.deleteDatabase(name) }
    }
}
