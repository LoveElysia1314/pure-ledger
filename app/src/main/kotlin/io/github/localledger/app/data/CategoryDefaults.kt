package io.github.localledger.app.data

import android.content.ContentValues
import io.github.localledger.core.*

fun LedgerStore.setCategoriesHidden(type: TransactionType, ids: List<String>, hidden: Boolean) = transaction { db ->
    require(ids.isNotEmpty() && ids.size == ids.distinct().size) { "请选择分类" }
    ids.forEach { id ->
        val position=CategoryStyle.next(db,type,hidden)
        require(db.update("categories", ContentValues().apply { put("hidden", if (hidden) 1 else 0);put("position",position) },
            "id=? AND type=?", arrayOf(id, type.name)) == 1) { "分类目录已变化，请重新选择" }
    }
}

fun LedgerStore.restoreCategoryDefaults(type: TransactionType): Int = transaction { db ->
    require(type != TransactionType.TRANSFER) { "分类名称或类型无效" }
    CategoryPack.ensure(db)
    val presets=CategoryPresets.defaults.associateBy { it.key }
    val rows=db.rawQuery("SELECT id,preset_key FROM categories WHERE type=? AND preset_key IS NOT NULL",arrayOf(type.name)).use { c -> buildList {
        while(c.moveToNext())add(c.getString(0) to c.getString(1))
    } }
    rows.forEach { (id,key) -> presets[key]?.let { preset ->
        db.update("categories",ContentValues().apply {
            put("name",io.github.localledger.app.CatalogLabels.preset(preset));put("icon",preset.symbol)
            put("position",CategoryPresets.rank(preset.name,type));put("hidden",0)
        },"id=?",arrayOf(id))
    } }
    rows.count { it.second in presets }
}
