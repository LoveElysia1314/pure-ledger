package io.github.localledger.core

import java.time.*
import kotlin.test.*

class LedgerPeriodTest {
    @Test fun `month boundaries cover leap February and year transitions in the selected zone`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val original = LedgerQuery(text = "咖啡", bookId = "travel", type = TransactionType.EXPENSE)
        val feb = original.inMonth(YearMonth.of(2024, 2), zone)
        assertEquals(original, feb.inMonth(null))
        assertEquals(LocalDate.of(2024, 2, 1), Instant.ofEpochMilli(feb.fromMs!!).atZone(zone).toLocalDate())
        assertEquals(29L, Duration.ofMillis(feb.untilMs!! - feb.fromMs!!).toDays())
        val jan = original.inMonth(YearMonth.of(2026, 12).plusMonths(1), zone)
        assertEquals(LocalDate.of(2027, 1, 1), Instant.ofEpochMilli(jan.fromMs!!).atZone(zone).toLocalDate())
    }
}
