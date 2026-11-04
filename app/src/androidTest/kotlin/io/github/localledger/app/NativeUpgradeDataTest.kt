package io.github.localledger.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.data.LedgerStore
import io.github.localledger.app.data.saveCandidate
import io.github.localledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/** Multi-process probe: all data stays inside the disposable AVD application directory. */
@RunWith(AndroidJUnit4::class)
class NativeUpgradeDataTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
    }

    private fun databaseState(): JSONObject = LedgerStore(context).use { store ->
        val db = store.readableDatabase
        val tables = db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND (name NOT LIKE 'sqlite_%' OR name='sqlite_sequence') ORDER BY name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        val all = JSONArray()
        for ((table, schema) in tables) {
            val escaped = table.replace("\"", "\"\"")
            val rows = db.rawQuery("SELECT * FROM \"$escaped\"", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(JSONArray().apply {
                        for (column in 0 until cursor.columnCount) put(when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> "integer:" + cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> "float:" + cursor.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> "blob:" + digest(cursor.getBlob(column))
                            else -> "text:" + cursor.getString(column)
                        })
                    }.toString())
                }.sorted()
            }
            all.put(JSONObject().put("table", table).put("schema", schema).put("rows", JSONArray(rows)))
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse("Broken database reference", it.moveToFirst()) }
        db.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        JSONObject().put("version", db.version).put("tables", all)
    }

    private fun preferenceState(): JSONObject {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        return JSONObject().apply {
            directory.listFiles()?.filter { it.extension == "xml" }?.sortedBy { it.name }?.forEach { file ->
                val prefs = context.getSharedPreferences(file.nameWithoutExtension, Context.MODE_PRIVATE)
                put(file.name, JSONObject().apply {
                    prefs.all.toSortedMap().forEach { (key, value) ->
                        put(key, when (value) {
                            is Set<*> -> "set:" + value.map { it.toString() }.sorted().joinToString("\u0000")
                            else -> "${value?.javaClass?.simpleName}:$value"
                        })
                    }
                })
            }
        }
    }

    private fun seed() {
        val baseline = File(context.filesDir, "signing-upgrade-baseline.json")
        check(!baseline.exists()) { "A baseline already exists; never overwrite upgrade evidence" }
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("automatic_updates", false).putString("signing_probe_note", "synthetic-preserved-preference")
            .putLong("signing_probe_long", Long.MAX_VALUE).commit()
        LedgerStore(context).use { store ->
            val prefix = "signature-${System.nanoTime()}"
            val book = store.saveBook("$prefix 合成账本")
            val cash = store.saveAccount("$prefix 合成现金", BigInteger("900719925474099312345"))
            val bank = store.saveAccount("$prefix 合成银行卡", BigInteger("100000"))
            val category = store.saveCategory(TransactionType.EXPENSE, "$prefix 合成分类", icon = "meal")
            val recognized = TransactionCandidate("com.tencent.mm", TransactionType.EXPENSE, Money(1801),
                "合成签名验证商户", "$prefix-order", null, "synthetic-signing-v1", 1793728800000L)
            store.saveCandidate(ConfirmedEntry(recognized, note = "合成识别凭据", bookId = book, accountId = cash, categoryId = category), "$prefix-recognized")
            store.createEntry(EntryDraft(TransactionType.INCOME, Money(123456789), "合成收入", 1793728801000L,
                "多行备注\n第二行", book, accountId = bank), "$prefix-income")
            store.createEntry(EntryDraft(TransactionType.TRANSFER, Money(2345), "合成转账", 1793728802000L,
                bookId = book, accountId = bank, toAccountId = cash, fee = Money(6), discount = Money(1)), "$prefix-transfer")
            val removed = store.createEntry(EntryDraft(TransactionType.EXPENSE, Money(77), "合成删除标记", 1793728803000L,
                bookId = book, categoryId = category, accountId = cash), "$prefix-deleted")
            store.deleteEntry(removed, 0)
            store.saveCategory(TransactionType.EXPENSE, "$prefix 合成分类", category, "meal", hidden = true)
        }
        File(context.filesDir, "signing-upgrade-retained-file.txt").writeText("synthetic persistent file\n不属于 ZIP 导出范围的文件也应保留\n")
    }

    @Test fun actualPackageUpgradePreservesTheCompleteDatabasePreferencesAndUid() {
        assertEquals("LocalLedger_API35", shell("getprop ro.boot.qemu.avd_name"))
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        val phase = arguments.getString("upgrade_phase") ?: error("Use the upgrade check harness")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        assertEquals("io.github.localledger.app", context.packageName)
        assertEquals(arguments.getString("expected_version_code")!!.toLong(), info.longVersionCode)
        val signing = requireNotNull(info.signingInfo)
        val signer = digest(signing.apkContentsSigners.single().toByteArray())
        assertEquals(arguments.getString("expected_signer"), signer)
        val baselineFile = File(context.filesDir, "signing-upgrade-baseline.json")
        if (phase == "seed") seed()
        val state = JSONObject().put("database", databaseState()).put("preferences", preferenceState())
            .put("uid", android.os.Process.myUid()).put("retained_file_sha256", digest(File(context.filesDir, "signing-upgrade-retained-file.txt").readBytes()))
        val hash = digest(state.toString().toByteArray())
        if (phase == "seed") baselineFile.writeText(state.toString()) else {
            assertEquals("Complete application data changed during package upgrade", baselineFile.readText(), state.toString())
            assertFalse("Production application must not be debuggable", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            assertTrue("Expected certificate rotation history", signing.hasPastSigningCertificates())
            assertTrue(signing.signingCertificateHistory.map { digest(it.toByteArray()) }.contains(arguments.getString("legacy_signer")))
        }
        val summary = JSONObject().put("phase", phase).put("state_sha256", hash).put("uid", android.os.Process.myUid())
            .put("version_code", info.longVersionCode).put("signer_sha256", signer).put("database_version", state.getJSONObject("database").getInt("version"))
            .put("table_count", state.getJSONObject("database").getJSONArray("tables").length())
        instrumentation.sendStatus(0, Bundle().apply { putString("upgrade_result", summary.toString()) })
    }
}
