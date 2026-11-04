package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.*
import io.github.localledger.app.CatalogLabels
import java.util.UUID

internal object CategoryPack {
    fun id(preset: CategoryPreset) = UUID.nameUUIDFromBytes("global-category-v1\u0000${preset.key}".toByteArray(Charsets.UTF_8)).toString()
    private fun legacyId(book: String, preset: CategoryPreset) = UUID.nameUUIDFromBytes("category-pack-v1\u0000$book\u0000${preset.type}\u0000${preset.name}".toByteArray(Charsets.UTF_8)).toString()
    fun pristine(rows: List<LedgerRow>, catalog: LedgerCatalog) = rows.isEmpty() && GlobalAccounts.pristine(catalog.accounts) &&
        catalog.books.size == 1 && catalog.books.single().id == DEFAULT_BOOK && CatalogLabels.defaultLedger(catalog.books.single().name) && catalog.categories.all { category ->
            CategoryPresets.defaults.any { p ->
                CatalogLabels.originalName(p, category.name) && p.type == category.type && id(p) == category.id &&
                    category.icon == p.symbol && !category.hidden
            }
        } && catalog.categories.groupBy { it.type to it.hidden }.values.all { group ->
            group.sortedBy { it.position }.map { it.id } == group.sortedBy { CategoryPresets.rank(
                CategoryPresets.defaults.find { p -> p.key == it.presetKey }?.name ?: it.name, it.type) }.map { it.id }
        }
    fun ensure(db: SQLiteDatabase, presets: List<CategoryPreset> = CategoryPresets.defaults): Int {
        var added = 0
        presets.forEach { preset ->
            val exists = db.rawQuery("SELECT 1 FROM categories WHERE type=? AND preset_key=?", arrayOf(preset.type.name, preset.key)).use { it.moveToFirst() }
            if (!exists) {
                val hidden = false
                val category = LedgerCategory(id(preset).takeUnless { db.hasId("categories", it) } ?: UUID.randomUUID().toString(),
                    preset.type, CatalogLabels.preset(preset), preset.symbol, CategoryStyle.next(db, preset.type, hidden), hidden, preset.key)
                db.insertOrThrow("categories", null, GlobalCategories.values(category)); added++
            }
        }
        return added
    }
    fun legacySeeded(catalog: LedgerCatalog): Boolean {
        val origins=legacyOrigins(catalog.books.map { it.id })
        return catalog.categories.any { it.presetKey!=null && origins[it.id]?.key==it.presetKey }
    }
    fun introduceNewIncome(db: SQLiteDatabase) {
        val newKeys=setOf("INCOME:稿费","INCOME:利息","INCOME:AA")
        val existing=db.rawQuery("SELECT type,name FROM categories",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0) to c.getString(1)) } }
        ensure(db,CategoryPresets.defaults.filter { preset -> preset.key in newKeys && existing.none { (type,name)->type==preset.type.name && CatalogLabels.originalName(preset,name) } })
    }
    fun legacyOrigins(books: Iterable<String>) = buildMap<String, CategoryPreset> {
        books.forEach { book -> CategoryPresets.legacy.forEach { preset -> put(legacyId(book, preset), preset) } }
    }
    fun upgradeOrigins(db: SQLiteDatabase) {
        val exists = db.rawQuery("PRAGMA table_info(categories)", null).use { c -> var found=false; while(c.moveToNext()) if(c.getString(1)=="preset_key") found=true; found }
        if (!exists) db.execSQL("ALTER TABLE categories ADD COLUMN preset_key TEXT")
        val books = db.rawQuery("SELECT id FROM books", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        val origins = legacyOrigins(books)
        val rows = db.rawQuery("SELECT id,type FROM categories WHERE preset_key IS NULL", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        rows.forEach { (id, type) -> origins[id]?.takeIf { it.type.name == type }?.let { preset ->
            db.update("categories", ContentValues().apply { put("preset_key", preset.key) }, "id=?", arrayOf(id))
        } }
    }
}
