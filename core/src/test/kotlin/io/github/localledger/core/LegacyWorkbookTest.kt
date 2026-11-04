package io.github.localledger.core

import io.github.localledger.core.migration.LegacyWorkbook
import kotlin.test.*

class LegacyWorkbookTest {
    @Test fun legacyAmountsStayExactInIntegerStorage() {
        assertEquals(123456L, LegacyWorkbook.money("￥1,234.56"))
        assertEquals(9007199254740991L, LegacyWorkbook.money("90,071,992,547,409.91"))
        assertFailsWith<ArithmeticException> { LegacyWorkbook.money("0.001") }
        assertFailsWith<IllegalArgumentException> { LegacyWorkbook.money("-1.00") }
    }
    @Test fun optionalFieldsDoNotEraseValidationOnRequiredFields() {
        assertEquals(0L, LegacyWorkbook.money("", optional = true))
        assertFailsWith<IllegalArgumentException> { LegacyWorkbook.money("") }
    }
    @Test fun variableWidthLegacyDateIsStrict() {
        assertEquals(LegacyWorkbook.time("2026-01-02 03:04"), LegacyWorkbook.time("2026-1-2 03:04"))
        assertFailsWith<IllegalArgumentException> { LegacyWorkbook.time("2026-2-30 03:04") }
    }
    @Test fun arbitraryFilesCannotBecomeImportDocuments() {
        assertFailsWith<IllegalArgumentException> { LegacyWorkbook.parse("synthetic non workbook".toByteArray()) }
    }
}
