package io.github.localledger.app

import io.github.localledger.core.*
import java.util.Locale

/** Preset display names are localized without rewriting stored or user-defined names. */
internal object CatalogLabels {
    private val presets = mapOf(
        "INCOME:AA" to R.string.preset_income_aa_collections,
        "EXPENSE:三餐" to R.string.preset_expense_meals,
        "EXPENSE:交通" to R.string.preset_expense_transport,
        "EXPENSE:咖啡饮料" to R.string.preset_expense_coffee_drinks,
        "EXPENSE:买菜" to R.string.preset_expense_groceries,
        "EXPENSE:水果" to R.string.preset_expense_fruit,
        "EXPENSE:零食" to R.string.preset_expense_snacks,
        "EXPENSE:宝贝" to R.string.preset_expense_childcare,
        "EXPENSE:服饰" to R.string.preset_expense_clothing,
        "EXPENSE:学习" to R.string.preset_expense_education,
        "EXPENSE:运动健身" to R.string.preset_expense_fitness,
        "EXPENSE:日用品" to R.string.preset_expense_daily_essentials,
        "EXPENSE:美妆" to R.string.preset_expense_beauty,
        "EXPENSE:住房" to R.string.preset_expense_housing,
        "EXPENSE:汽车" to R.string.preset_expense_car,
        "EXPENSE:水电煤" to R.string.preset_expense_utilities,
        "EXPENSE:娱乐" to R.string.preset_expense_entertainment,
        "EXPENSE:追星" to R.string.preset_expense_fandom,
        "EXPENSE:医疗" to R.string.preset_expense_healthcare,
        "EXPENSE:电器数码" to R.string.preset_expense_electronics,
        "EXPENSE:通讯" to R.string.preset_expense_phone,
        "EXPENSE:宠物" to R.string.preset_expense_pets,
        "EXPENSE:烟酒" to R.string.preset_expense_tobacco_alcohol,
        "EXPENSE:礼品" to R.string.preset_expense_gifts,
        "EXPENSE:发红包" to R.string.preset_expense_red_packets_sent,
        "EXPENSE:旅行" to R.string.preset_expense_travel,
        "EXPENSE:其他支出" to R.string.preset_expense_other_expense,
        "INCOME:工资" to R.string.preset_income_salary,
        "INCOME:奖金" to R.string.preset_income_bonus,
        "INCOME:分红" to R.string.preset_income_dividends,
        "INCOME:租金" to R.string.preset_income_rent,
        "INCOME:生活费" to R.string.preset_income_allowance,
        "INCOME:理财" to R.string.preset_income_investments,
        "INCOME:外快" to R.string.preset_income_side_income,
        "INCOME:收红包" to R.string.preset_income_red_packets_received,
        "INCOME:其他收入" to R.string.preset_income_other_income,
        "EXPENSE:购物" to R.string.preset_expense_shopping,
        "EXPENSE:包包" to R.string.preset_expense_bags,
        "EXPENSE:家具" to R.string.preset_expense_furniture,
        "EXPENSE:酒店" to R.string.preset_expense_hotels,
        "EXPENSE:球鞋" to R.string.preset_expense_shoes,
        "EXPENSE:面霜" to R.string.preset_expense_face_cream,
        "EXPENSE:乳液" to R.string.preset_expense_lotion,
        "EXPENSE:手机" to R.string.preset_expense_mobile_phone,
        "EXPENSE:电脑" to R.string.preset_expense_computer,
        "EXPENSE:聚会" to R.string.preset_expense_gatherings,
        "EXPENSE:电影" to R.string.preset_expense_movies,
        "EXPENSE:恋爱" to R.string.preset_expense_dating,
        "EXPENSE:加油充电" to R.string.preset_expense_fuel_charging,
        "EXPENSE:药品" to R.string.preset_expense_medicine,
        "EXPENSE:书籍" to R.string.preset_expense_books,
        "EXPENSE:孕育" to R.string.preset_expense_pregnancy_baby,
        "EXPENSE:瘦身" to R.string.preset_expense_weight_management,
        "EXPENSE:目标" to R.string.preset_expense_goals,
        "EXPENSE:到此一游" to R.string.preset_expense_places_visited,
        "INCOME:稿费" to R.string.preset_income_royalties,
        "INCOME:利息" to R.string.preset_income_interest,
        "INCOME:手续费" to R.string.preset_income_service_fees,
        "INCOME:辛苦费" to R.string.preset_income_tips,
        "INCOME:目标奖励" to R.string.preset_income_goal_rewards
    )
    private val icons = mapOf(
        "wine" to R.string.icon_wine,"handshake" to R.string.icon_handshake,"award" to R.string.icon_award,"rent" to R.string.icon_rent,
        "envelopein" to R.string.icon_envelopein,"envelopeout" to R.string.icon_envelopeout,"incomeplus" to R.string.icon_incomeplus,"coins" to R.string.icon_coins,
        "interest" to R.string.icon_interest,"aacollection" to R.string.icon_aacollection,"reimbursement" to R.string.icon_reimbursement,"refund" to R.string.icon_refund,
        "meal" to R.string.icon_meal,
        "cup" to R.string.icon_cup,
        "fruit" to R.string.icon_fruit,
        "snack" to R.string.icon_snack,
        "basket" to R.string.icon_basket,
        "transport" to R.string.icon_transport,
        "car" to R.string.icon_car,
        "travel" to R.string.icon_travel,
        "phone" to R.string.icon_phone,
        "device" to R.string.icon_device,
        "home" to R.string.icon_home,
        "wallet" to R.string.icon_wallet,
        "chart" to R.string.icon_chart,
        "bag" to R.string.icon_bag,
        "beauty" to R.string.icon_beauty,
        "gift" to R.string.icon_gift,
        "heart" to R.string.icon_heart,
        "baby" to R.string.icon_baby,
        "pet" to R.string.icon_pet,
        "shirt" to R.string.icon_shirt,
        "book" to R.string.icon_book,
        "sport" to R.string.icon_sport,
        "medical" to R.string.icon_medical,
        "bolt" to R.string.icon_bolt,
        "game" to R.string.icon_game,
        "star" to R.string.icon_star,
        "grid" to R.string.icon_grid,
        "wifi" to R.string.icon_wifi,
        "work" to R.string.icon_work,
        "calendar" to R.string.icon_calendar,
        "music" to R.string.icon_music,
        "camera" to R.string.icon_camera,
        "tools" to R.string.icon_tools
    )
    fun preset(value: CategoryPreset) = appString(presets.getValue(value.key))
    fun icon(key: String) = appString(icons.getValue(key))
    private val locales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.ENGLISH, Locale.JAPANESE)
    private val knownNames by lazy { presets.mapValues { (_, resource) -> locales.map { AppStrings.inLocale(it, resource) }.toSet() } }
    fun originalName(value: CategoryPreset, name: String) = name == value.name || name in knownNames.getValue(value.key)
    fun generatedPresetName(value: CategoryPreset, name: String): Boolean =
        (knownNames.getValue(value.key) + value.name).any { base ->
            name.startsWith(base) && name.removePrefix(base).matches(Regex("(?:(?:（(?:内置|內建|標準)[0-9]*）)|(?: \\(built-in(?: [0-9]+)?\\)))+"))
        }
    fun category(value: LedgerCategory): String {
        val resource = presets[value.presetKey] ?: return value.name
        return if (value.name in knownNames.getValue(value.presetKey!!)) appString(resource) else value.name
    }
    fun other(type: TransactionType) = appString(if (type == TransactionType.INCOME) R.string.preset_income_other_income else R.string.preset_expense_other_expense)
    fun category(catalog: LedgerCatalog, id: String?, type: TransactionType) = catalog.categories.find { it.id == id }?.let(::category)
        ?: if(type==TransactionType.TRANSFER)appString(R.string.ui_transfer) else other(type)
    fun fallback(catalog: LedgerCatalog, type: TransactionType): String? {
        if (type == TransactionType.TRANSFER) return null
        val key = if (type == TransactionType.INCOME) "INCOME:其他收入" else "EXPENSE:其他支出"
        val visible = catalog.categories.filter { it.type == type && !it.hidden }
        return (visible.find { it.presetKey == key } ?: visible.firstOrNull())?.id
    }
    fun defaultLedger(name: String) = name == AppStrings.inLocale(Locale.SIMPLIFIED_CHINESE, R.string.ui_default_ledger) || name == AppStrings.inLocale(Locale.ENGLISH, R.string.ui_default_ledger)
}

internal val LedgerCategory.displayName: String get() = CatalogLabels.category(this)
