package io.github.localledger.core

data class CategoryPreset(val name: String, val type: TransactionType, val symbol: String) {
    val key: String get() = "${type.name}:$name"
}

/** Flat everyday categories with persistent meanings for previously saved source keys. */
object CategoryPresets {
    private fun items(type: TransactionType, vararg groups: Pair<String, String>) = groups.flatMap { (icon, names) ->
        names.split("、").map { CategoryPreset(it, type, icon) }
    }
    val defaults = items(TransactionType.EXPENSE,
        "meal" to "三餐", "transport" to "交通", "cup" to "咖啡饮料", "basket" to "买菜", "fruit" to "水果", "snack" to "零食",
        "baby" to "宝贝", "shirt" to "服饰", "book" to "学习", "sport" to "运动健身", "bag" to "日用品", "beauty" to "美妆",
        "home" to "住房", "car" to "汽车", "bolt" to "水电煤", "game" to "娱乐", "star" to "追星", "medical" to "医疗",
        "device" to "电器数码", "phone" to "通讯", "pet" to "宠物", "wine" to "烟酒", "gift" to "礼品", "envelopeout" to "发红包",
        "travel" to "旅行", "grid" to "其他支出") + items(TransactionType.INCOME,
        "work" to "工资", "award" to "奖金", "coins" to "分红", "rent" to "租金", "wallet" to "生活费",
        "chart" to "理财", "tools" to "外快", "envelopein" to "收红包", "incomeplus" to "其他收入",
        "book" to "稿费", "interest" to "利息", "aacollection" to "AA")

    /** Former directory and naming candidates, retained for existing IDs and imported configurations. */
    val legacy = items(TransactionType.EXPENSE,
        "meal" to "三餐", "transport" to "交通", "cup" to "咖啡饮料", "basket" to "买菜", "fruit" to "水果", "snack" to "零食",
        "baby" to "宝贝", "shirt" to "服饰", "book" to "学习", "sport" to "运动健身", "bag" to "日用品", "beauty" to "美妆",
        "home" to "住房", "car" to "汽车", "bolt" to "水电煤", "game" to "娱乐", "star" to "追星", "medical" to "医疗",
        "device" to "电器数码", "phone" to "通讯", "pet" to "宠物", "cup" to "烟酒", "gift" to "礼品", "gift" to "发红包",
        "travel" to "旅行", "grid" to "其他支出") + items(TransactionType.INCOME,
        "wallet" to "工资", "star" to "奖金", "chart" to "分红", "home" to "租金", "wallet" to "生活费", "chart" to "理财",
        "wallet" to "外快", "gift" to "收红包", "grid" to "其他收入") + items(TransactionType.EXPENSE,
        "bag" to "购物、包包", "home" to "家具、酒店", "shirt" to "球鞋", "beauty" to "面霜、乳液", "phone" to "手机",
        "device" to "电脑", "meal" to "聚会", "game" to "电影", "heart" to "恋爱", "car" to "加油充电", "medical" to "药品",
        "book" to "书籍", "baby" to "孕育", "sport" to "瘦身", "star" to "目标", "travel" to "到此一游") +
        items(TransactionType.INCOME, "book" to "稿费", "chart" to "利息", "wallet" to "手续费、辛苦费", "star" to "目标奖励")
    val known = (legacy + defaults).distinctBy { it.key }

    /** Apply only to an untouched legacy preset that still has its previous default symbol. */
    val legacyIconUpgrades = mapOf(
        "EXPENSE:烟酒" to "wine", "EXPENSE:发红包" to "envelopeout",
        "INCOME:工资" to "work", "INCOME:奖金" to "award", "INCOME:分红" to "coins",
        "INCOME:租金" to "rent", "INCOME:外快" to "tools", "INCOME:收红包" to "envelopein", "INCOME:利息" to "interest",
        "INCOME:其他收入" to "incomeplus")

    fun symbol(name: String): String = (defaults + known).firstOrNull { it.name == name }?.symbol ?: when {
        listOf("餐", "饭", "食品").any(name::contains) -> "meal"
        listOf("车", "地铁", "公交").any(name::contains) -> "transport"
        listOf("医疗", "药", "健康").any(name::contains) -> "medical"
        listOf("工资", "收入").any(name::contains) -> "wallet"
        else -> "grid"
    }
    fun rank(name: String, type: TransactionType): Int = (defaults + known).distinctBy { it.key }
        .indexOfFirst { it.name == name && it.type == type }.takeIf { it >= 0 } ?: Int.MAX_VALUE
}
