package io.github.localledger.core

import kotlin.test.*
import org.junit.jupiter.api.Test

class AppVersionTest {
    @Test fun numericPublicVersionsSortAcrossMinorAndMajorChanges() {
        val versions = listOf("0.6.1", "0.6.9", "0.6.10", "0.10.0", "1.0.0").map { AppVersion.parse(it)!! }
        assertEquals(versions, versions.reversed().sorted())
        assertEquals(60100, versions.first().sortKey)
        assertEquals(1_000_000, versions.last().sortKey)
        assertEquals(versions.first(), AppVersion.parse("v0.6.1"))
    }
    @Test fun rejectsPrereleasesAmbiguousNumbersAndUninstallableVersions() {
        listOf("", "1.0", "v1.0.0-beta.1", "1.0.0+5", "01.0.0", "1.100.0", "1.0.100", "-1.0.0",
            "99999999999999.0.0", "2100.0.1", "2101.0.0", " 1.0.0", "V1.0.0").forEach { assertNull(AppVersion.parse(it), it) }
        assertEquals(2_100_000_000, AppVersion.parse("2100.0.0")!!.sortKey)
    }
}
