package io.github.localledger.app.ui

import io.github.localledger.app.R
import io.github.localledger.app.appString
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.github.localledger.app.AppStrings
import io.github.localledger.core.TransactionType

/** Presentation only: raw evidence and unknown imported columns remain in storage/export. */
internal fun sourceDetails(source: String, raw: String): List<Pair<String, String>> = runCatching {
    val json = JSONObject(raw)
    fun value(key: String) = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
    if (source != "RECOGNIZED") return@runCatching json.keys().asSequence().mapNotNull { key -> value(key)?.let { key to it } }.toList()
    buildList {
        value("app_package")?.let { app -> add(appString(R.string.source_application) to when (app) {
            "com.tencent.mm" -> appString(R.string.source_wechat)
            "com.eg.android.AlipayGphone" -> appString(R.string.source_alipay)
            else -> app
        }) }
        value("external_id")?.let { add(appString(R.string.transaction_number) to it) }
        value("source_merchant")?.let { add(appString(R.string.ui_counterparty) to it) }
        value("source_type")?.let { type -> TransactionType.entries.find { it.name == type }?.let { add(appString(R.string.source_transaction_type) to typeTitle(it)) } }
        value("source_amount_minor")?.let { amount -> runCatching { BigDecimal(BigInteger(amount), 2).toPlainString() }.getOrNull()?.let { add(appString(R.string.ui_amount_cny) to it) } }
        value("source_time")?.toLongOrNull()?.let { stamp -> runCatching { Instant.ofEpochMilli(stamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", AppStrings.locale)) }.getOrNull()?.let { add(appString(R.string.ui_transaction_time) to it) } }
    }
}.getOrDefault(emptyList())
