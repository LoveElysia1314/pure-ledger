package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.app.CatalogLabels
import io.github.localledger.core.*

internal data class CategoryNormalization(val categories: List<LedgerCategory>, val aliases: Map<String, String>)

/** Shared by the database upgrade and exchange files written before categories became global. */
internal object GlobalCategories {
    fun create(db: SQLiteDatabase, table: String = "categories") {
        check(table in setOf("categories", "categories_v9"))
        db.execSQL("""CREATE TABLE $table(id TEXT PRIMARY KEY NOT NULL,
            type TEXT NOT NULL CHECK(type IN ('EXPENSE','INCOME')),name TEXT NOT NULL,
            icon TEXT NOT NULL DEFAULT 'grid',position INTEGER NOT NULL DEFAULT 0,
            hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN (0,1)),preset_key TEXT)""")
        db.execSQL("CREATE UNIQUE INDEX categories_preset_origin ON $table(preset_key) WHERE preset_key IS NOT NULL")
    }
    fun normalize(categories: List<LedgerCategory>): CategoryNormalization {
        val presets = CategoryPresets.known.associateBy { it.key }
        val cleaned = categories.map { category ->
            val preset = presets[category.presetKey]
            val named=if (preset != null && CatalogLabels.generatedPresetName(preset, category.name))category.copy(name = CatalogLabels.preset(preset)) else category
            if(preset!=null && named.icon==preset.symbol && CatalogLabels.originalName(preset,named.name))
                named.copy(icon=CategoryPresets.legacyIconUpgrades[named.presetKey] ?: named.icon) else named
        }
        fun identity(c: LedgerCategory): List<String?> {
            val preset = presets[c.presetKey]
            val name = if (preset != null && CatalogLabels.originalName(preset, c.name)) preset.key else c.name
            return listOf(c.type.name, name, c.icon, c.presetKey)
        }
        // Different names or artwork retain their own identity; visibility is enabled if any old book enabled it.
        val aliases = mutableMapOf<String, String>()
        val merged = cleaned.groupBy(::identity).values.map { group ->
            val original = group.minWith(compareBy({ it.position }, { it.id }))
            group.forEach { aliases[it.id] = original.id }
            original.copy(hidden = group.all { it.hidden })
        }
        val owners=merged.filter { it.presetKey!=null }.groupBy { it.presetKey }.mapValues { (_,group) ->
            group.minWith(compareBy<LedgerCategory>({ c -> if(CatalogLabels.originalName(presets.getValue(c.presetKey!!),c.name))0 else 1 },{it.position},{it.id})).id
        }
        val distinctOrigins=merged.map { c -> if(c.presetKey!=null && owners[c.presetKey]!=c.id)c.copy(presetKey=null) else c }
        val ordered = distinctOrigins.groupBy { it.type to it.hidden }.values.flatMap { group ->
            group.sortedWith(compareBy({ it.position }, { it.name }, { it.id }))
                .mapIndexed { index, category -> category.copy(position = index.toLong()) }
        }
        return CategoryNormalization(ordered, aliases)
    }
    fun values(category: LedgerCategory) = ContentValues().apply {
        put("id", category.id); put("type", category.type.name); put("name", category.name)
        put("icon", category.icon); put("position", category.position); put("hidden", if (category.hidden) 1 else 0)
        put("preset_key", category.presetKey)
    }
    fun upgrade(db: SQLiteDatabase) {
        val legacy = db.rawQuery("PRAGMA table_info(categories)", null).use { c ->
            var found = false; while (c.moveToNext()) if (c.getString(1) == "book_id") found = true; found
        }
        if (!legacy) return
        val old = db.rawQuery("SELECT id,type,name,icon,position,hidden,preset_key FROM categories ORDER BY id", null).use { c -> buildList {
            while (c.moveToNext()) add(LedgerCategory(c.getString(0), TransactionType.valueOf(c.getString(1)), c.getString(2),
                c.getString(3), c.getLong(4), c.getInt(5) == 1, c.getString(6)))
        } }
        val result = normalize(old)
        create(db, "categories_v9")
        result.categories.forEach { db.insertOrThrow("categories_v9", null, values(it)) }
        result.aliases.filter { it.key != it.value }.forEach { (oldId, newId) ->
            listOf("entries", "selection_recent", "merchant_choices").forEach { table ->
                db.update(table, ContentValues().apply { put("category_id", newId) }, "category_id=?", arrayOf(oldId))
            }
        }
        db.execSQL("DROP TABLE categories")
        db.execSQL("ALTER TABLE categories_v9 RENAME TO categories")
    }
}
