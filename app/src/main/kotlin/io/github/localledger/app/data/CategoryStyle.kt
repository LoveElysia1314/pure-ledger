package io.github.localledger.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.localledger.core.*

internal object CategoryStyle {
    fun upgrade(db: SQLiteDatabase) {
        val hasStyle = db.rawQuery("PRAGMA table_info(categories)", null).use { c ->
            var found = false; while (c.moveToNext()) if (c.getString(1) == "icon") found = true; found
        }
        if (hasStyle) return
        db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT NOT NULL DEFAULT 'grid'")
        db.execSQL("ALTER TABLE categories ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE categories ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN (0,1))")
        val all = db.rawQuery("SELECT id,book_id,type,name FROM categories", null).use { c -> buildList {
            while (c.moveToNext()) add(c.getString(1) to LedgerCategory(c.getString(0), TransactionType.valueOf(c.getString(2)), c.getString(3)))
        } }
        all.groupBy { it.first to it.second.type }.values.forEach { group ->
            group.map { it.second }.sortedWith(compareBy({ CategoryPresets.rank(it.name, it.type) }, { it.name }, { it.id })).forEachIndexed { index, category ->
                db.update("categories", ContentValues().apply { put("icon", category.icon); put("position", index) }, "id=?", arrayOf(category.id))
            }
        }
    }
    fun next(db: SQLiteDatabase, type: TransactionType, hidden: Boolean = false): Long = db.rawQuery(
        "SELECT COALESCE(MAX(position),-1)+1 FROM categories WHERE type=? AND hidden=?", arrayOf(type.name, if(hidden)"1" else "0")
    ).use { it.moveToFirst(); it.getLong(0).also { position -> require(position in 0 until Long.MAX_VALUE) { "分类顺序超出范围" } } }
}

fun LedgerStore.reorderCategories(type: TransactionType, ids: List<String>) = transaction { db ->
    val actual = db.rawQuery("SELECT id FROM categories WHERE type=?", arrayOf(type.name))
        .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    require(ids.size == ids.distinct().size && ids.toSet() == actual) { "分类目录已变化，请重新排序" }
    ids.forEachIndexed { index, id -> db.update("categories", ContentValues().apply { put("position", index) }, "id=?", arrayOf(id)) }
}
fun LedgerStore.arrangeCategories(type: TransactionType, enabled: List<String>, hidden: List<String>) = transaction { db ->
    val actual = db.rawQuery("SELECT id FROM categories WHERE type=?",arrayOf(type.name)).use { c -> buildSet { while(c.moveToNext())add(c.getString(0)) } }
    val ids=enabled+hidden
    require(ids.size==ids.distinct().size && ids.toSet()==actual){"分类目录已变化，请重新排序"}
    listOf(false to enabled,true to hidden).forEach { (hide,group) -> group.forEachIndexed { index,id ->
        db.update("categories",ContentValues().apply { put("position",index);put("hidden",if(hide)1 else 0) },"id=?",arrayOf(id))
    } }
}
fun LedgerStore.setCategoryHidden(id: String, hidden: Boolean) = transaction { db ->
    require(db.update("categories", ContentValues().apply { put("hidden", if (hidden) 1 else 0) }, "id=?", arrayOf(id)) == 1) { "分类不存在" }
}
