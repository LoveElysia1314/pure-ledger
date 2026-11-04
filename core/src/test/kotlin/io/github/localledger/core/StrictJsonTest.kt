package io.github.localledger.core

import kotlin.test.*

class StrictJsonTest {
    @Test fun `duplicates comments trailing commas and coercion syntax are rejected`() {
        listOf("{\"a\":1,\"a\":2}", "{a:1}", "{'a':1}", "[1,]", "{\"a\":1,}", "true false", "01", "/*x*/{}")
            .forEach { value -> assertFailsWith<IllegalArgumentException> { StrictJson.validate(value) } }
    }
    @Test fun `nested records escaped fields and valid numbers are accepted`() {
        StrictJson.validate("{\"text\":\"原样\\n文字\",\"rows\":[null,true,false,-1.5e2],\"object\":{}}")
        assertFailsWith<IllegalArgumentException> { StrictJson.validate("{\"a\":1,\"\\u0061\":2}") }
        assertFailsWith<IllegalArgumentException> { StrictJson.validate("[".repeat(70) + "0" + "]".repeat(70)) }
        StrictJson.validate("\"\\uD83D\\uDE00\"")
        listOf("\"\\u+123\"", "\"\\u-123\"", "\"\\uD83D\"", "\"\\uDE00\"").forEach {
            value -> assertFailsWith<IllegalArgumentException> { StrictJson.validate(value) }
        }
    }
}
