package io.github.localledger.core

import java.math.BigDecimal
import java.math.BigInteger

/** Decimal keypad arithmetic, in integer cents throughout. Never evaluates arbitrary code. */
object EntryAmount {
    fun edit(expression: String, key: String): String {
        if (key == "00") return edit(edit(expression, "0"), "0")
        if (key == "delete") return expression.dropLast(1)
        if (key == "+" || key == "-") {
            if (expression.isEmpty()) return expression
            return expression.trimEnd('+', '-') + key
        }
        require(key == "." || key.length == 1 && key[0] in '0'..'9')
        if (expression.length >= 160) return expression
        val last = expression.substringAfterLast('+').substringAfterLast('-')
        if (key == ".") return if (last.contains('.')) expression else expression + (if (last.isEmpty()) "0." else ".")
        if (last.contains('.') && last.substringAfter('.').length >= 2) return expression
        return if (last == "0") expression.dropLast(1) + key else expression + key
    }

    fun evaluate(expression: String): Money {
        require(expression.isNotEmpty() && expression.length <= 160 &&
            Regex("[0-9]+(?:\\.[0-9]{0,2})?(?:[+-][0-9]+(?:\\.[0-9]{0,2})?)*").matches(expression)) { "请填写完整金额" }
        var result = BigInteger.ZERO
        Regex("([+-]?)([0-9]+(?:\\.[0-9]{0,2})?)").findAll(expression).forEach {
            val cents = BigDecimal(it.groupValues[2]).movePointRight(2).toBigIntegerExact()
            result += if (it.groupValues[1] == "-") -cents else cents
        }
        require(result.signum() >= 0) { "金额不能为负数" }
        return try { Money(result.longValueExact()) } catch (_: ArithmeticException) { throw IllegalArgumentException("金额超出范围") }
    }
}
