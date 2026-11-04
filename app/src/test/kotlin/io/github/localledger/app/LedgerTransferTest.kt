package io.github.localledger.app

import android.content.Context
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import io.github.localledger.core.migration.LegacyWorkbook
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.io.ByteArrayOutputStream
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerTransferTest {
    private lateinit var context: Context
    private lateinit var store: LedgerStore
    private val name = "synthetic-import.db"
    @Before fun prepare() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(name); context.deleteDatabase("synthetic-target.db"); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name); context.deleteDatabase("synthetic-target.db") }
    @Test fun identicalLegitimateRowsSurviveAndRepeatedImportsAreSkipped() {
        val document = LegacyWorkbook.parse(workbookFixture(listOf("10.00", "10.00"), true))
        assertTrue(document.problems.isEmpty())
        assertEquals(2, store.previewImport(document).added)
        assertEquals(2, store.commitImport(document).added)
        assertEquals(0, store.commitImport(document).added)
        assertEquals(2, store.snapshot().count)
        val row = store.snapshot().rows.first()
        val raw = JSONObject(row.rawSource)
        assertEquals("原始附加内容", raw.getString("自定义列")); assertEquals("2.00", raw.getString("退款总额"))
        assertEquals("https://example.invalid/receipt.png", raw.getString("账单图片"))
        assertEquals(BigInteger.ZERO, store.snapshot().catalog.accounts.single().balanceMinor)
        assertFalse(store.snapshot().catalog.accounts.single().balanceKnown)
    }
    @Test fun zeroAmountRowsAndWhitespaceSurviveImportAndExchange() {
        val document = LegacyWorkbook.parse(workbookFixture(listOf("0.00", "10.00")))
        assertTrue(store.previewImport(document).problems.isEmpty())
        store.commitImport(document)
        val id = store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(0), "  对方  ", 1, "\n  保留空白  \n"))
        assertEquals("\n  保留空白  \n", store.entry(id)!!.note)
        val packet = LedgerExchange.decode(store.exportExchange())
        LedgerStore(context, "synthetic-target.db").use { target ->
            target.commitExchange(packet)
            assertEquals(3, target.snapshot().count)
            assertEquals(store.snapshot().rows.map { it.copy(id = 0) }.sortedBy { it.uid }, target.snapshot().rows.map { it.copy(id = 0) }.sortedBy { it.uid })
        }
    }
    @Test fun conflictsRequireAnExplicitKeepBothChoice() {
        val before = LegacyWorkbook.parse(workbookFixture(listOf("10.00")))
        store.commitImport(before)
        val changed = LegacyWorkbook.parse(workbookFixture(listOf("11.00")))
        assertEquals(1, store.previewImport(changed).conflicts.size)
        try { store.commitImport(changed); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.snapshot().count)
        store.commitImport(changed, keepConflicts = true)
        assertEquals(2, store.snapshot().count)
    }
    @Test fun importFaultRollsBackRowsMappingsAndReceipts() {
        val before = store.snapshot().catalog
        val document = LegacyWorkbook.parse(workbookFixture(listOf("10.00", "11.00")))
        store.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON import_receipts WHEN NEW.source_row=3 BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { store.commitImport(document); fail() } catch (_: android.database.SQLException) { }
        val state = store.snapshot()
        assertEquals(0, state.count); assertEquals(before, state.catalog)
        store.writableDatabase.execSQL("DROP TRIGGER fail_import")
        assertEquals(2, store.commitImport(document).added)
    }
    @Test fun invalidPreviewCannotBePartiallyCommitted() {
        val document = LegacyWorkbook.parse(workbookFixture(listOf("10.00", "1.001")))
        assertFalse(store.previewImport(document).problems.isEmpty())
        try { store.commitImport(document); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, store.snapshot().count)
    }
    @Test fun optionalBalancePostingAndCalibrationHaveSeparateEffects() {
        val doc = LegacyWorkbook.parse(workbookFixture(listOf("10.00")))
        store.commitImport(doc, affectBalances = true)
        val account = store.snapshot().catalog.accounts.single()
        assertEquals(BigInteger.valueOf(-1000), account.balanceMinor)
        store.saveAccount(account.name, BigInteger.valueOf(5000), account.id)
        assertEquals(BigInteger.valueOf(5000), store.snapshot().catalog.accounts.single().balanceMinor)
    }
    @Test fun exchangeRoundTripKeepsBalancesRawFieldsReceiptsAndDeletedMarkers() {
        val doc = LegacyWorkbook.parse(workbookFixture(listOf("10.00", "10.00"), true)); store.commitImport(doc)
        val account = store.snapshot().catalog.accounts.single()
        store.saveAccount(account.name, BigInteger.valueOf(12345), account.id)
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(300), "样例店铺",
            "synthetic-receipt-001", "2026-10-31 12:00:00", "test-v1", 1793419200000)
        store.save(ConfirmedEntry(source, bookId = store.snapshot().rows.first().bookId, accountId = account.id))
        val destination = store.saveAccount("另一账户", BigInteger.ZERO)
        store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(500), "", 1, bookId = store.snapshot().rows.first().bookId,
            accountId = account.id, toAccountId = destination, fee = Money(20)))
        val oldestImport = store.snapshot().rows.first { it.source == "IMPORT" }; store.deleteEntry(oldestImport.id, oldestImport.revision)
        val bytes = store.exportExchange(); val packet = LedgerExchange.decode(bytes)
        LedgerStore(context, "synthetic-target.db").use { target ->
            assertTrue(target.previewExchange(packet).conflicts.isEmpty())
            target.commitExchange(packet)
            assertEquals(store.snapshot().catalog.accounts, target.snapshot().catalog.accounts)
            assertEquals(store.snapshot().rows.map { it.copy(id = 0) }.sortedBy { it.uid }, target.snapshot().rows.map { it.copy(id = 0) }.sortedBy { it.uid })
            assertEquals(SaveResult.AlreadySaved, target.save(ConfirmedEntry(source)))
            assertEquals(0, target.commitExchange(packet).added)
            assertEquals(0, target.commitImport(doc).added)
            assertEquals(store.snapshot().count, target.snapshot().count)
            val restored = LedgerExchange.decode(target.exportExchange())
            assertEquals(packet.entries.map { it.copy(id = 0) }.sortedBy { it.uid }, restored.entries.map { it.copy(id = 0) }.sortedBy { it.uid })
            assertEquals(packet.recognition, restored.recognition); assertEquals(packet.imports, restored.imports)
        }
    }
    @Test fun staleExchangeConflictCannotOverwriteExistingEdits() {
        val id = store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(100), "样例", 1))
        val packet = LedgerExchange.decode(store.exportExchange())
        store.updateEntry(id, 0, store.entry(id)!!.draft().copy(amount = Money(200)))
        assertFalse(store.previewExchange(packet).conflicts.isEmpty())
        try { store.commitExchange(packet); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(200L, store.entry(id)!!.amount.minor)
    }
    private fun changeZip(bytes: ByteArray, member: String, transform: (ByteArray) -> ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        ZipInputStream(bytes.inputStream()).use { source -> ZipOutputStream(result).use { target ->
            while (true) {
                val entry = source.nextEntry ?: break
                val content = source.readBytes()
                target.putNextEntry(ZipEntry(entry.name)); target.write(if (entry.name == member) transform(content) else content); target.closeEntry()
            }
        } }
        return result.toByteArray()
    }
    @Test fun corruptHashUnknownVersionAndBrokenReferencesAreRejected() {
        val bytes = store.exportExchange()
        val badHash = changeZip(bytes, "records.json") { it + ' '.code.toByte() }
        val badVersion = changeZip(bytes, "manifest.json") { JSONObject(it.toString(Charsets.UTF_8)).put("schemaVersion", 99).toString().toByteArray() }
        listOf(badHash, badVersion).forEach { try { LedgerExchange.decode(it); fail() } catch (_: IllegalArgumentException) { } }
        val data = LedgerExchange.decode(bytes)
        val orphan = LedgerRow(0, TransactionType.EXPENSE, Money(1), "样例", 1, "", uid = "orphan", accountId = "missing")
        try { LedgerExchange.decode(LedgerExchange.encode(data.copy(entries = listOf(orphan)))); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, store.snapshot().count)
    }
    @Test fun alteredEvidenceAndNonCanonicalReceiptNumbersAreRejected() {
        val source = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(300), "样例", "synthetic-integrity", null, "v1", 123)
        store.save(ConfirmedEntry(source))
        store.commitImport(LegacyWorkbook.parse(workbookFixture(listOf("1.00"))))
        val data = LedgerExchange.decode(store.exportExchange())
        listOf("source_amount_minor" to "0300", "evidence_hash" to "a".repeat(64)).forEach { (field, value) ->
            val receipt = data.recognition.single().let { it.copy(fields = it.fields + (field to value)) }
            try { LedgerExchange.decode(LedgerExchange.encode(data.copy(recognition = listOf(receipt)))); fail() } catch (_: IllegalArgumentException) { }
        }
        listOf("source_row" to "02", "source_key" to data.imports.single().fields.getValue("source_key").substringBefore(':') + ":-1").forEach { (field, value) ->
            val receipt = data.imports.single().let { it.copy(fields = it.fields + (field to value)) }
            try { LedgerExchange.decode(LedgerExchange.encode(data.copy(imports = listOf(receipt)))); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(2, store.snapshot().count)
    }
}
