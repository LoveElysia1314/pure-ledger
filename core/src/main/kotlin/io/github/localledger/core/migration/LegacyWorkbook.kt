package io.github.localledger.core.migration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.SAXException

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun canonical(values: List<String>): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        for (value in values) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            out.writeInt(encoded.size)
            out.write(encoded)
        }
    }
    return sha256(bytes.toByteArray())
}

internal fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}

internal fun rawJson(values: Map<String, String>): String = values.entries.joinToString(",", "{", "}") {
    jsonString(it.key) + ":" + jsonString(it.value)
}

data class ImportRow(
    val sheet: String, val sourceRow: Int, val kind: Int, val book: String,
    val occurred: Long, val recorded: Long, val category: String, val amount: Long,
    val account: String, val fromAccount: String, val toAccount: String,
    val remark: String, val image: String, val recordType: Int,
    val refund: Long, val needsReimbursement: Boolean, val reimbursement: Long,
    val reimbursementAccount: String, val reimbursementTime: Long,
    val fee: Long, val discount: Long, val raw: Map<String, String>,
    val fingerprint: String, val identity: String, val occurrence: Int
)

data class ImportProblem(val sheet: String, val row: Int, val message: String)
data class ImportDocument(val fileHash: String, val rows: List<ImportRow>,
                          val problems: List<ImportProblem>, val unknownColumns: Set<String>)

/** The legacy export contains literal cells. Formula evaluation and external XML are forbidden. */
object LegacyWorkbook {
    val columns = linkedMapOf(
        "支出" to listOf("所属账本", "支出时间", "支出分类", "支出金额", "支出账户", "备注", "账单图片", "记录时间", "记录方式", "退款总额", "待报销", "报销金额", "报销入账账户", "报销时间"),
        "收入" to listOf("所属账本", "收入时间", "收入分类", "收入金额", "收入账户", "备注", "账单图片", "记录时间", "记录方式", "退款总额"),
        "转账" to listOf("所属账本", "转账时间", "转账金额", "转出账户", "转入账户", "转账手续费", "优惠", "备注", "账单图片", "记录时间", "记录方式")
    )
    private val ways = listOf("", "智能记账", "语音记账", "手动记账", "周期记账", "模板记账", "快速记账", "自动记账")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val times = listOf("uuuu-M-d HH:mm", "uuuu-M-d HH:mm:ss").map {
        DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT)
    }

    fun money(text: String, optional: Boolean = false): Long {
        var value = text.trim().removePrefix("￥").removePrefix("¥").removeSuffix("元").trim()
        if (value.isEmpty() && optional) return 0
        require(Regex("[+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?").matches(value)) { "金额格式错误或为负数" }
        value = value.replace(",", "")
        val amount = BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY)
        val minor = amount.movePointRight(2).longValueExact()
        return minor
    }

    fun time(text: String, optional: Boolean = false): Long {
        if (optional && text.isBlank()) return 0
        for (formatter in times) try {
            return LocalDateTime.parse(text.trim(), formatter).atZone(zone).toInstant().toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) { }
        throw IllegalArgumentException("日期应为 年-月-日 时:分（可含秒），按北京时间解释")
    }

    private fun xml(bytes: ByteArray): Element {
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true) && !text.contains('\u0000')) { "不接受 XML 实体或非 UTF-8 内容" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("禁止外部资源") }
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.elements(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.textRuns(): String = elements("t").filter { t ->
        var parent = t.parentNode
        var phonetic = false
        while (parent != null && parent !== this) {
            if (parent.localName == "rPh") phonetic = true
            parent = parent.parentNode
        }
        !phonetic
    }.joinToString("") { it.textContent }

    fun parse(bytes: ByteArray): ImportDocument {
        require(bytes.size in 1..20 * 1024 * 1024) { "文件为空或超过 20 MB" }
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entries.size < 10_000 && entry.name.split('/').none { it == ".." } && !entry.name.startsWith('/')) { "压缩包路径或数量不合法" }
                require(!entries.containsKey(entry.name)) { "压缩包有重名文件" }
                val buffer = ByteArrayOutputStream()
                val block = ByteArray(8192)
                while (true) {
                    val count = zip.read(block)
                    if (count < 0) break
                    total += count
                    require(total <= 100 * 1024 * 1024 && buffer.size() + count <= 40 * 1024 * 1024) { "解压数据超过上限" }
                    buffer.write(block, 0, count)
                }
                entries[entry.name] = buffer.toByteArray()
            }
        }
        fun member(name: String): ByteArray = entries[name] ?: throw IllegalArgumentException("不是完整的 XLSX 文件")
        val workbook = xml(member("xl/workbook.xml"))
        val relations = xml(member("xl/_rels/workbook.xml.rels")).elements("Relationship")
            .filter { it.getAttribute("TargetMode") != "External" }
            .associate { it.getAttribute("Id") to it.getAttribute("Target") }
        val shared = entries["xl/sharedStrings.xml"]?.let { xml(it).elements("si").map { si -> si.textRuns() } } ?: emptyList()
        val problems = mutableListOf<ImportProblem>()
        val rows = mutableListOf<ImportRow>()
        val unknown = linkedSetOf<String>()
        val sheets = workbook.elements("sheet")
        require(sheets.map { it.getAttribute("name") }.distinct().size == sheets.size) { "工作表名称重复" }
        for ((sheet, expected) in columns) {
            val definition = sheets.singleOrNull { it.getAttribute("name") == sheet }
            if (definition == null) { problems.add(ImportProblem(sheet, 0, "缺少工作表")); continue }
            val rel = definition.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = relations[rel] ?: throw IllegalArgumentException("工作表关系无效")
            require(!target.contains('\\') && target.split('/').none { it == ".." }) { "工作表路径无效" }
            val path = if (target.startsWith('/')) target.drop(1) else "xl/$target"
            require(path.startsWith("xl/")) { "工作表路径无效" }
            val xmlRows = xml(member(path)).elements("row")
            require(xmlRows.size <= 50_001) { "单表超过 50,000 行" }
            fun values(row: Element): Map<String, String> {
                val result = linkedMapOf<String, String>()
                for (cell in row.elements("c")) {
                    require(cell.elements("f").isEmpty()) { "不接受公式单元格，请使用原版直接导出文件" }
                    val address = cell.getAttribute("r")
                    require(Regex("[A-Z]{1,2}[1-9][0-9]*").matches(address)) { "单元格地址无效" }
                    val column = address.takeWhile { it in 'A'..'Z' }
                    require(!result.containsKey(column)) { "单元格地址重复" }
                    val value = cell.elements("v").firstOrNull()?.textContent ?: ""
                    result[column] = when (cell.getAttribute("t")) {
                        "s" -> shared.getOrNull(value.toIntOrNull() ?: -1) ?: throw IllegalArgumentException("共享文字索引无效")
                        "inlineStr" -> cell.textRuns()
                        "", "n", "str" -> value
                        else -> throw IllegalArgumentException("不接受错误或布尔类型的账单单元格")
                    }
                }
                return result
            }
            if (xmlRows.isEmpty()) { problems.add(ImportProblem(sheet, 0, "没有表头")); continue }
            val headers = values(xmlRows.first()).mapValues { it.value.trim() }
            if (headers.values.filter { it.isNotEmpty() }.distinct().size != headers.values.count { it.isNotEmpty() }) {
                problems.add(ImportProblem(sheet, 1, "表头名称重复")); continue
            }
            val missing = expected.filter { it !in headers.values }
            if (missing.isNotEmpty()) { problems.add(ImportProblem(sheet, 1, "缺少列：${missing.joinToString("、")}")); continue }
            unknown.addAll(headers.values.filter { it.isNotEmpty() && it !in expected })
            val occurrences = mutableMapOf<String, Int>()
            for ((position, row) in xmlRows.drop(1).withIndex()) {
                val rowNumber = row.getAttribute("r").toIntOrNull() ?: position + 2
                try {
                    val cells = values(row)
                    if (cells.values.all { it.isBlank() }) continue
                    require(cells.keys.all { !headers[it].isNullOrBlank() || cells[it].isNullOrBlank() }) { "有数据的列缺少表头" }
                    val raw = linkedMapOf<String, String>()
                    for ((column, title) in headers) if (title.isNotEmpty()) raw[title] = cells[column] ?: ""
                    val parsed = parseRow(sheet, rowNumber, raw)
                    val occurrence = occurrences[parsed.fingerprint] ?: 0
                    occurrences[parsed.fingerprint] = occurrence + 1
                    rows.add(parsed.copy(occurrence = occurrence))
                } catch (e: IllegalArgumentException) {
                    problems.add(ImportProblem(sheet, rowNumber, e.message ?: "字段无效"))
                } catch (_: ArithmeticException) {
                    problems.add(ImportProblem(sheet, rowNumber, "金额精度或范围无效"))
                }
            }
        }
        return ImportDocument(sha256(bytes), rows, problems, unknown)
    }

    private fun parseRow(sheet: String, row: Int, raw: Map<String, String>): ImportRow {
        val known = columns.getValue(sheet)
        fun text(name: String) = if (name in known) raw[name]?.trim() ?: "" else ""
        fun name(key: String) = if (key in known) raw[key] ?: "" else ""
        fun amount(name: String, optional: Boolean = true) = money(text(name), optional)
        val kind = columns.keys.indexOf(sheet) + 1
        val book = name("所属账本")
        require(book.isNotBlank()) { "所属账本为空" }
        val occurred = time(text("${sheet}时间"))
        val recorded = time(text("记录时间"))
        val category = if (kind == 3) "" else name("${sheet}分类")
        val amount = amount("${sheet}金额", false)
        val account = if (kind == 3) "" else name("${sheet}账户")
        val from = name("转出账户")
        val to = name("转入账户")
        val way = text("记录方式")
        require(way in ways) { "无法识别记录方式" }
        val recordType = ways.indexOf(way)
        val refund = amount("退款总额")
        val flag = text("待报销")
        require(flag in listOf("", "是", "否")) { "待报销字段无效" }
        val reimbursement = amount("报销金额")
        val reimTime = time(text("报销时间"), true)
        val fee = amount("转账手续费")
        val discount = amount("优惠")
        val semantic = listOf(sheet, book, occurred.toString(), recorded.toString(), category, amount.toString(),
            account, from, to, raw["备注"] ?: "", text("账单图片"), recordType.toString(), refund.toString(),
            flag, reimbursement.toString(), name("报销入账账户"), reimTime.toString(), fee.toString(), discount.toString()) +
            raw.filterKeys { it !in columns.getValue(sheet) }.toSortedMap().flatMap { listOf(it.key, it.value) }
        return ImportRow(sheet, row, kind, book, occurred, recorded, category, amount, account, from, to,
            raw["备注"] ?: "", text("账单图片"), recordType, refund, flag == "是", reimbursement,
            name("报销入账账户"), reimTime, fee, discount, raw.toMap(), canonical(semantic),
            canonical(listOf(sheet, book, occurred.toString(), recorded.toString(), account, from, to)), 0)
    }
}
