package io.github.localledger.core

import kotlin.test.*

class EntryAmountTest {
    @Test fun `keypad keeps cents exact and replaces an unfinished operator`() {
        var text = ""
        listOf("0", ".", "1", "+", "0", ".", "2").forEach { text = EntryAmount.edit(text, it) }
        assertEquals(Money(30), EntryAmount.evaluate(text))
        assertEquals("0.1+0.2-", EntryAmount.edit(EntryAmount.edit(text, "+"), "-"))
        assertEquals("0.1+0.2", EntryAmount.edit("0.1+0.2-", "delete"))
        assertEquals("12.30", EntryAmount.edit("12.3", "00"))
        assertEquals("12.34", EntryAmount.edit("12.34", "5"))
        assertEquals("0.", EntryAmount.edit("", "."))
    }
    @Test fun `expressions reject negatives overflow precision and unfinished input`() {
        assertEquals(Money(100), EntryAmount.evaluate("2-1"))
        assertEquals(Money(0), EntryAmount.evaluate("1.00-1"))
        assertEquals(Money(Long.MAX_VALUE), EntryAmount.evaluate("92233720368547758.07+0"))
        listOf("1-2", "1+", "", "1.001", "92233720368547758.08", "1*2", "-1", "1++2", "1\n").forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { EntryAmount.evaluate(text) }
        }
    }
}
