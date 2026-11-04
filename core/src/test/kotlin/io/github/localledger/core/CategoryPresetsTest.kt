package io.github.localledger.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CategoryPresetsTest {
    @Test fun dailyCatalogHasBothDirectionsAndStableOrder() {
        assertEquals(26, CategoryPresets.defaults.count { it.type == TransactionType.EXPENSE })
        assertEquals(12, CategoryPresets.defaults.count { it.type == TransactionType.INCOME })
        assertEquals(listOf("三餐", "交通", "咖啡饮料", "买菜"), CategoryPresets.defaults.take(4).map { it.name })
        assertEquals(listOf("工资", "奖金", "分红", "租金", "生活费", "理财", "外快", "收红包", "其他收入", "稿费", "利息", "AA"),
            CategoryPresets.defaults.filter { it.type == TransactionType.INCOME }.map { it.name })
        val all = CategoryPresets.defaults
        assertEquals(all.size, all.distinctBy { it.type to it.name }.size)
        assertTrue(all.none { it.type == TransactionType.TRANSFER || it.symbol.isBlank() })
        assertTrue(CategoryPresets.rank("工资", TransactionType.INCOME) < CategoryPresets.rank("自定", TransactionType.INCOME))
    }
    @Test fun oldSourceKeysKeepTheirNamesWhileTheDefaultArtworkChanges() {
        val legacy = CategoryPresets.legacy.associateBy { it.key }
        val current = CategoryPresets.defaults.associateBy { it.key }
        val known = CategoryPresets.known.associateBy { it.key }
        assertEquals(59, legacy.size)
        assertTrue(known.keys.containsAll(legacy.keys + current.keys))
        assertTrue(listOf("EXPENSE:宝贝", "EXPENSE:水电煤", "EXPENSE:追星", "EXPENSE:礼品", "EXPENSE:发红包").all { it in current })
        assertTrue(listOf("INCOME:稿费", "INCOME:分红", "INCOME:利息", "INCOME:AA").all { it in current })
        assertFalse(listOf("INCOME:报销", "INCOME:退款").any { it in current })
        assertFalse(current.keys.any { it.endsWith(":AA收款") })
        assertEquals("cup", known.getValue("EXPENSE:烟酒").symbol)
        assertEquals("wine", current.getValue("EXPENSE:烟酒").symbol)
        assertEquals("wallet", known.getValue("INCOME:工资").symbol)
        assertEquals("work", current.getValue("INCOME:工资").symbol)
        assertEquals(legacy.getValue("EXPENSE:手机"), known.getValue("EXPENSE:手机"))
    }
    @Test fun eachDefaultDirectionUsesDistinctArtworkAndAllSavedSymbolsRemainAvailable() {
        CategoryPresets.defaults.groupBy { it.type }.values.forEach { group ->
            assertEquals(group.size, group.map { it.symbol }.distinct().size)
        }
        assertTrue(CategoryIcons.keys.containsAll(CategoryPresets.known.map { it.symbol }))
        assertTrue(CategoryIcons.keys.containsAll(CategoryPresets.legacyIconUpgrades.values))
        assertTrue(CategoryPresets.legacyIconUpgrades.keys.all { key -> CategoryPresets.legacy.any { it.key == key } })
    }
    @Test fun importedNamesUseDeterministicSymbolsWithoutChangingTheirNames() {
        assertEquals("meal", CategoryPresets.symbol("工作餐"))
        assertEquals("medical", CategoryPresets.symbol("常备药"))
        assertEquals("grid", CategoryPresets.symbol("自定分类"))
        assertEquals("device", CategoryPresets.symbol("电器数码"))
    }
}
