package io.github.localledger.core

import java.math.BigDecimal
import java.math.RoundingMode

/** First-version CNY amount. Conversion fails rather than silently rounding. */
@JvmInline
value class Money(val minor: Long) {
    fun decimal(): BigDecimal = BigDecimal.valueOf(minor, 2)

    companion object {
        fun parseCny(input: String): Money {
            val text = input.trim().removePrefix("人民币").removePrefix("￥")
                .removePrefix("¥").removePrefix("CNY").trim().removeSuffix("元").trim()
            require(Regex("[+-]?(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?").matches(text)) {
                "金额格式不明确"
            }
            return Money(BigDecimal(text.replace(",", ""))
                .setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact())
        }
    }
}
