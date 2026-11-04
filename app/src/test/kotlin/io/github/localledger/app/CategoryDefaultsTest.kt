package io.github.localledger.app

import android.content.ContentValues
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class CategoryDefaultsTest {
    private lateinit var store: LedgerStore
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "synthetic-category-defaults.db"
    private val expense = TransactionType.EXPENSE
    @Before fun setup() { context.deleteDatabase(name); store = LedgerStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name) }
    private fun packet() = LedgerExchange.decode(store.exportExchange())

    @Test fun resetRestoresPresetSettingsWithoutRenamingCustomCategoriesOrChangingBills() {
        val builtin = store.snapshot().catalog.categories.single { it.name == "三餐" }
        store.saveCategory(expense, "午餐", builtin.id, "cup", true)
        val custom = store.saveCategory(expense, "三餐", icon = "gift", hidden = true)
        store.createEntry(EntryDraft(expense, Money(5), "合成", 1, categoryId = builtin.id))
        store.createEntry(EntryDraft(expense, Money(6), "合成", 2, categoryId = custom))
        val before = packet(); val customBefore = before.categories.single { it.id == custom }
        val incomeBefore = before.categories.filter { it.type == TransactionType.INCOME }
        store.restoreCategoryDefaults(expense)
        val after = packet()
        assertEquals(before.entries, after.entries)
        assertEquals(customBefore, after.categories.single { it.id == custom })
        assertEquals(incomeBefore, after.categories.filter { it.type == TransactionType.INCOME })
        val restored = after.categories.single { it.id == builtin.id }
        assertEquals("三餐", restored.name); assertEquals("meal", restored.icon); assertFalse(restored.hidden)
        assertEquals(builtin.presetKey, restored.presetKey)
        assertFalse(after.categories.any { "内置" in it.name })
        store.restoreCategoryDefaults(expense)
        assertEquals(after, packet())
    }
    @Test fun swappedNamesAndDeletedPresetsRestoreWhileCustomAndLegacySettingsRemain() {
        val all = store.snapshot().catalog.categories
        val meal = all.single { it.name == "三餐" }; val traffic = all.single { it.name == "交通" }
        store.saveCategory(expense, "临时", meal.id)
        store.saveCategory(expense, "三餐", traffic.id)
        store.saveCategory(expense, "交通", meal.id)
        store.deleteCatalog("categories", all.single { it.name == "宠物" }.id)
        val custom = store.saveCategory(expense, "自定义", icon = "heart", hidden = true)
        val legacy = store.saveCategory(expense, "电影", icon = "camera", hidden = true)
        store.writableDatabase.update("categories", ContentValues().apply { put("preset_key", "EXPENSE:电影") }, "id=?", arrayOf(legacy))
        val keep = store.snapshot().catalog.categories.filter { it.id == custom || it.id == legacy }
        store.restoreCategoryDefaults(expense)
        val result = store.snapshot().catalog.categories
        assertEquals("三餐", result.single { it.id == meal.id }.name)
        assertEquals("交通", result.single { it.id == traffic.id }.name)
        assertTrue(result.any { it.presetKey == "EXPENSE:宠物" })
        assertEquals(keep, result.filter { it.id == custom || it.id == legacy })
        assertEquals(1, result.count { it.presetKey == "EXPENSE:电影" })
    }
    @Test fun batchHidingIsGlobalAtomicAndKeepsHistoricalRowsAndBalances() {
        val other = store.saveBook("其他")
        val rows = store.snapshot().catalog.categories
        val picked = rows.filter { it.type == expense }.take(3).map { it.id }
        val wrongType = rows.first { it.type == TransactionType.INCOME }.id
        val account = store.saveAccount("合成现金", java.math.BigInteger.ZERO)
        store.createEntry(EntryDraft(expense, Money(100), "合成", 1, categoryId = picked.first(), accountId = account))
        store.createEntry(EntryDraft(expense, Money(200), "合成", 2, bookId = other, categoryId = picked.first(), accountId = account))
        val before = packet()
        try { store.setCategoriesHidden(expense, picked + wrongType, true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, packet())
        store.setCategoriesHidden(expense, picked, true)
        val after = packet()
        assertEquals(before.entries, after.entries); assertEquals(before.accounts, after.accounts)
        assertEquals(picked.toSet(), after.categories.filter { it.hidden }.map { it.id }.toSet())
        val firstBook = store.snapshot(query = LedgerQuery(bookId = DEFAULT_BOOK))
        val secondBook = store.snapshot(query = LedgerQuery(bookId = other))
        assertEquals(firstBook.catalog.categories, secondBook.catalog.categories)
        assertEquals(picked.first(), firstBook.rows.single().categoryId)
        assertEquals(picked.first(), secondBook.rows.single().categoryId)
        store.setCategoriesHidden(expense, picked, false)
        assertTrue(store.snapshot().catalog.categories.none { it.hidden })
        assertEquals(before.entries, packet().entries)
    }
    @Test fun movingBetweenEnabledAndHiddenGroupsPreservesTheChosenOrderAcrossBooks() {
        val other = store.saveBook("其他")
        val ids = store.snapshot().catalog.categories.filter { it.type == expense }.map { it.id }
        val hidden = ids.take(2).reversed()
        val enabled = ids.drop(2).reversed()
        store.arrangeCategories(expense, enabled, hidden)
        fun assertGroups(book: String) {
            val categories = store.snapshot(query = LedgerQuery(bookId = book)).catalog.categories.filter { it.type == expense }
            assertEquals(enabled, categories.filterNot { it.hidden }.map { it.id })
            assertEquals(hidden, categories.filter { it.hidden }.map { it.id })
        }
        assertGroups(DEFAULT_BOOK); assertGroups(other)
        store.arrangeCategories(expense, listOf(hidden.last()) + enabled, hidden.dropLast(1))
        val result = store.snapshot().catalog.categories.filter { it.type == expense }
        assertEquals(hidden.last(), result.first { !it.hidden }.id)
        assertEquals(hidden.dropLast(1), result.filter { it.hidden }.map { it.id })
    }
    @Test fun invalidOrFailedCrossGroupArrangementLeavesTheWholeConfigurationUnchanged() {
        val before = packet()
        val ids = before.categories.filter { it.type == expense }.map { it.id }
        val income = before.categories.first { it.type == TransactionType.INCOME }.id
        listOf(ids.dropLast(1) to emptyList(), ids to listOf(ids.first()), ids.dropLast(1) to listOf(income)).forEach { (enabled, hidden) ->
            try { store.arrangeCategories(expense, enabled, hidden); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(before, packet())
        }
        val hidden = ids.first()
        store.writableDatabase.execSQL("CREATE TRIGGER fail_group BEFORE UPDATE ON categories WHEN NEW.id='$hidden' AND NEW.hidden=1 BEGIN SELECT RAISE(ABORT,'synthetic group fault'); END")
        try { store.arrangeCategories(expense, ids.drop(1).reversed(), listOf(hidden)); fail() } catch (_: android.database.SQLException) { }
        assertEquals(before, packet())
    }
    @Test fun restoreFailureRollsBackNamesStylesAndSeededRows() {
        val category = store.snapshot().catalog.categories.single { it.name == "三餐" }
        store.saveCategory(expense, "午餐", category.id, "cup", true)
        store.deleteCatalog("categories", store.snapshot().catalog.categories.single { it.name == "宠物" }.id)
        val before = packet()
        store.writableDatabase.execSQL("CREATE TRIGGER fail_reset BEFORE UPDATE OF icon ON categories WHEN NEW.name='交通' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        try { store.restoreCategoryDefaults(expense); fail() } catch (_: android.database.SQLException) { }
        assertEquals(before, packet())
    }
    @Test fun creatingADuplicateNameDoesNotTurnAnExistingPresetIntoACustomCategory() {
        val before = packet()
        try { store.saveCategory(expense, "三餐", icon = "gift", requireNew = true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(before, packet())
    }
}
