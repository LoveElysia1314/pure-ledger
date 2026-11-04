package io.github.localledger.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

/** Page labels and unambiguous dates shared by the two small payment parsers. */
internal object PaymentText {
    fun clean(value: String) = value.replace('\u00a0', ' ').replace('\u202f', ' ').replace("\u200b", "").replace('−', '-').trim()
    fun label(value: String) = clean(value).removeSuffix(":").removeSuffix("：").trim().lowercase(Locale.ROOT)
    private val patterns = buildList {
        for (date in listOf("uuuu-M-d", "uuuu/M/d", "uuuu年M月d日", "MMM d, uuuu", "MMMM d, uuuu", "d MMM uuuu", "d MMMM uuuu", "MMM d uuuu", "MMMM d uuuu")) {
            for (time in listOf("HH:mm:ss", "HH:mm", "h:mm:ss a", "h:mm a")) {
                add("$date $time")
                if (!date.startsWith("uuuu")) add("$date, $time")
            }
        }
        add("uuuu-M-d'T'HH:mm:ss")
    }.map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT) }
    fun time(value: String, zone: ZoneId): Long? = patterns.firstNotNullOfOrNull {
        runCatching { LocalDateTime.parse(clean(value).replace(Regex(" +"), " "), it).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }
    fun unsupportedCurrency(texts: List<String>): Boolean {
        val labels = texts.map(::label)
        val explicit = labels.indices.filter { labels[it] in setOf("币种", "currency") }.mapNotNull { labels.getOrNull(it+1) }
        return explicit.any { it !in setOf("cny", "rmb", "人民币", "chinese yuan", "yuan", "renminbi") } ||
            texts.any { Regex("(?i)(?:^|[\\s+\\-])(?:USD|HKD|EUR|GBP|JPY|AUD|SGD)\\s*[0-9]|[＄$€£]\\s*[0-9]").containsMatchIn(it) }
    }
}
