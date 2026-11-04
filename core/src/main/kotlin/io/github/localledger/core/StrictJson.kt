package io.github.localledger.core

/** Validate JSON before platform parsing: duplicate fields and lenient JSON extensions are rejected. */
object StrictJson {
    fun validate(text: String) {
        var position = 0
        fun invalid(): Nothing = throw IllegalArgumentException("数据 JSON 格式无效")
        fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        fun string(): String {
            if (text.getOrNull(position++) != '"') invalid()
            val result = StringBuilder()
            while (position < text.length) {
                val c = text[position++]
                if (c == '"') {
                    val value = result.toString(); var i = 0
                    while (i < value.length) {
                        if (value[i].isHighSurrogate()) { if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) invalid(); i += 2 }
                        else { if (value[i].isLowSurrogate()) invalid(); i++ }
                    }
                    return value
                }
                if (c.code < 32) invalid()
                if (c != '\\') { result.append(c); continue }
                when (val escape = text.getOrNull(position++) ?: invalid()) {
                    '"', '\\', '/' -> result.append(escape)
                    'b' -> result.append('\b'); 'f' -> result.append('\u000c')
                    'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                    'u' -> {
                        if (position + 4 > text.length) invalid()
                        val digits = text.substring(position, position + 4)
                        if (digits.any { it !in "0123456789abcdefABCDEF" }) invalid()
                        val code = digits.toInt(16)
                        position += 4; result.append(code.toChar())
                    }
                    else -> invalid()
                }
            }
            invalid()
        }
        fun value(depth: Int): Unit {
            if (depth > 64) invalid()
            whitespace()
            when (text.getOrNull(position)) {
                '{' -> {
                    position++; whitespace(); val keys = mutableSetOf<String>()
                    if (text.getOrNull(position) == '}') { position++; return }
                    while (true) {
                        whitespace(); val key = string(); if (!keys.add(key)) invalid()
                        whitespace(); if (text.getOrNull(position++) != ':') invalid()
                        value(depth + 1); whitespace()
                        when (text.getOrNull(position++)) { '}' -> return; ',' -> Unit; else -> invalid() }
                    }
                }
                '[' -> {
                    position++; whitespace()
                    if (text.getOrNull(position) == ']') { position++; return }
                    while (true) {
                        value(depth + 1); whitespace()
                        when (text.getOrNull(position++)) { ']' -> return; ',' -> Unit; else -> invalid() }
                    }
                }
                '"' -> { string(); return }
                't', 'f', 'n' -> {
                    val literal = when (text[position]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    if (!text.startsWith(literal, position)) invalid(); position += literal.length
                }
                else -> {
                    val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, position) ?: invalid()
                    if (match.range.first != position) invalid(); position = match.range.last + 1
                }
            }
        }
        value(0); whitespace(); if (position != text.length) invalid()
    }
}
