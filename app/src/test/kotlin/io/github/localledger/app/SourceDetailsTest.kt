package io.github.localledger.app

import io.github.localledger.app.ui.sourceDetails
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=LedgerApplication::class,qualifiers="en-rUS")
class SourceDetailsTest {
    @Test fun recognizedDetailsExposeExactTransactionValuesWithoutInternalEvidenceFields() {
        AppStrings.initialize(RuntimeEnvironment.getApplication())
        val raw=JSONObject().put("recognition_format","candidate-v1").put("source_key","internal-identity")
            .put("evidence_hash","internal-hash").put("rule_version","private-rule").put("duplicate_of_uid","internal-uid")
            .put("app_package","com.tencent.mm").put("external_id","SYNTHETIC-0001").put("source_amount_minor","9223372036854775807")
            .put("source_merchant","合成商户").put("source_type","EXPENSE").put("source_time","1793509200000").toString()
        val details=sourceDetails("RECOGNIZED",raw).toMap()
        assertEquals("WeChat",details["Source app"]);assertEquals("SYNTHETIC-0001",details["Transaction number"])
        assertEquals("92233720368547758.07",details["Amount (CNY)"]);assertEquals("合成商户",details["Counterparty"])
        assertEquals("Expense",details["Entry type"]);assertNotNull(details["Transaction time"])
        assertEquals(6,details.size)
        assertTrue(details.values.none{it.startsWith("internal-") || it=="private-rule"})
        assertEquals("internal-hash",JSONObject(raw).getString("evidence_hash"))
    }
    @Test fun importedUnknownColumnsAndUserTextRemainVisibleAndUnmodified() {
        val json=JSONObject().put("用户自定义列","备忘 note").put("退款","待处理").put("source_key","用户自己的字段").toString()
        assertEquals(mapOf("用户自定义列" to "备忘 note","退款" to "待处理","source_key" to "用户自己的字段"),sourceDetails("IMPORT",json).toMap())
        assertTrue(sourceDetails("RECOGNIZED","not-json").isEmpty())
    }
}
