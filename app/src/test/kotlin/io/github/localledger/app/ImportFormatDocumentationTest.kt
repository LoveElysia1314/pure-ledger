package io.github.localledger.app

import io.github.localledger.core.migration.LegacyWorkbook
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFormatDocumentationTest {
    @Test fun publishedHeadersAndSyntheticRowsAreAcceptedByTheRealImporter() {
        val guide = File("../docs/IMPORT.md").readText()
        val headerBlocks = Regex("```text\\n([^`]+)```").findAll(guide).take(3).map { it.groupValues[1].trim().split('\t') }.toList()
        assertEquals(LegacyWorkbook.columns.values.toList(), headerBlocks)
        val sheets = JSONObject(File("../examples/import/synthetic-example.json").readText()).getJSONObject("sheets")
        assertEquals(LegacyWorkbook.columns.keys, sheets.keys().asSequence().toSet())
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val names = LegacyWorkbook.columns.keys.toList()
        val members = linkedMapOf<String, String>()
        members["xl/workbook.xml"] = "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            names.mapIndexed { index, name -> "<sheet name=\"$name\" r:id=\"r$index\"/>" }.joinToString("") + "</sheets></workbook>"
        members["xl/_rels/workbook.xml.rels"] = "<Relationships>" + names.indices.joinToString("") {
            "<Relationship Id=\"r$it\" Target=\"worksheets/sheet$it.xml\"/>" } + "</Relationships>"
        names.forEachIndexed { index, name ->
            val columns = LegacyWorkbook.columns.getValue(name)
            val rows = sheets.getJSONArray(name)
            fun row(number: Int, values: List<String>) = "<row r=\"$number\">" + values.mapIndexed { column, value ->
                "<c r=\"${('A'.code + column).toChar()}$number\" t=\"inlineStr\"><is><t>${xml(value)}</t></is></c>"
            }.joinToString("") + "</row>"
            val data = (0 until rows.length()).joinToString("") { item ->
                val record = rows.getJSONObject(item)
                assertEquals(columns.toSet(), record.keys().asSequence().toSet())
                row(item + 2, columns.map { record.getString(it) })
            }
            members["xl/worksheets/sheet$index.xml"] = "<worksheet><sheetData>${row(1, columns)}$data</sheetData></worksheet>"
        }
        val bytes = ByteArrayOutputStream().also { stream -> ZipOutputStream(stream).use { zip ->
            members.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        } }.toByteArray()
        val document = LegacyWorkbook.parse(bytes)
        assertTrue(document.problems.toString(), document.problems.isEmpty())
        assertEquals(3, document.rows.size)
        assertEquals(listOf(1850L, 200000L, 50000L), document.rows.map { it.amount })
        assertEquals(100L, document.rows.last().fee)
        assertEquals("商户：合成餐厅；原备注：午餐", document.rows.first().remark)
    }
}
