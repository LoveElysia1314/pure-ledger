package io.github.localledger.app

import io.github.localledger.core.migration.LegacyWorkbook
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun workbookFixture(amounts: List<String>, extra: Boolean = false): ByteArray {
    fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
    val names = LegacyWorkbook.columns.keys.toList()
    val members = mutableMapOf<String, String>()
    members["xl/workbook.xml"] = "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
        names.mapIndexed { i, name -> "<sheet name=\"$name\" r:id=\"r$i\"/>" }.joinToString("") + "</sheets></workbook>"
    members["xl/_rels/workbook.xml.rels"] = "<Relationships>" + names.indices.joinToString("") { "<Relationship Id=\"r$it\" Target=\"worksheets/sheet$it.xml\"/>" } + "</Relationships>"
    names.forEachIndexed { index, sheet ->
        val columns = LegacyWorkbook.columns.getValue(sheet) + if (extra && index == 0) listOf("自定义列") else emptyList()
        fun row(number: Int, values: List<String>) = "<row r=\"$number\">" + values.mapIndexed { i, v ->
            val column = ('A'.code + i).toChar(); "<c r=\"$column$number\" t=\"inlineStr\"><is><t>${xml(v)}</t></is></c>"
        }.joinToString("") + "</row>"
        val rows = if (index != 0) "" else amounts.mapIndexed { i, amount ->
            val data = mapOf("所属账本" to "历史账本", "支出时间" to "2026-1-2 03:04", "支出分类" to "餐饮", "支出金额" to amount,
                "支出账户" to "现金", "备注" to "保留原始备注", "记录时间" to "2026-1-2 03:05", "记录方式" to "手动记账",
                "退款总额" to "2.00", "待报销" to "是", "报销金额" to "1.00", "账单图片" to "https://example.invalid/receipt.png", "自定义列" to "原始附加内容")
            row(i + 2, columns.map { data[it].orEmpty() })
        }.joinToString("")
        members["xl/worksheets/sheet$index.xml"] = "<worksheet><sheetData>${row(1, columns)}$rows</sheetData></worksheet>"
    }
    return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        members.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
    } }.toByteArray()
}
