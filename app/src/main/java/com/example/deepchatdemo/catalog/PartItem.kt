package com.example.deepchatdemo.catalog

import org.json.JSONObject

data class PartItem(
    val code: String,
    val nameCn: String,
    val nameEn: String,
    val brand: String,
    val models: String,
    val spec: String,
    val buyer: String,
    val unit: String,
    val standardPrice: Double?,
    val latestPrice: Double?,
    val latestPriceDate: String?,
    val latestPriceQty: Double?,
    val ctnQty: Double?,
    val grossWeightKg: Double?,
    val lengthCm: Double?,
    val widthCm: Double?,
    val heightCm: Double?,
    val volumeCbm: Double?,
    val remark: String,
    val searchText: String
) {
    companion object {
        fun fromJsonLine(line: String): PartItem {
            val json = JSONObject(line)
            return PartItem(
                code = json.optStringOrEmpty("code"),
                nameCn = json.optStringOrEmpty("nameCn"),
                nameEn = json.optStringOrEmpty("nameEn"),
                brand = json.optStringOrEmpty("brand"),
                models = json.optStringOrEmpty("models"),
                spec = json.optStringOrEmpty("spec"),
                buyer = json.optStringOrEmpty("buyer"),
                unit = json.optStringOrEmpty("unit"),
                standardPrice = json.optNullableDouble("standardPrice"),
                latestPrice = json.optNullableDouble("latestPrice"),
                latestPriceDate = json.optNullableString("latestPriceDate"),
                latestPriceQty = json.optNullableDouble("latestPriceQty"),
                ctnQty = json.optNullableDouble("ctnQty"),
                grossWeightKg = json.optNullableDouble("grossWeightKg"),
                lengthCm = json.optNullableDouble("lengthCm"),
                widthCm = json.optNullableDouble("widthCm"),
                heightCm = json.optNullableDouble("heightCm"),
                volumeCbm = json.optNullableDouble("volumeCbm"),
                remark = json.optStringOrEmpty("remark"),
                searchText = json.optStringOrEmpty("searchText")
            )
        }
    }
}

data class ScoredPartItem(
    val part: PartItem,
    val score: Int,
    val matchedTerms: List<String> = emptyList()
)

private fun JSONObject.optStringOrEmpty(name: String): String {
    if (!has(name) || isNull(name)) return ""
    return optString(name).trim()
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotBlank() }
}

private fun JSONObject.optNullableDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name) ?: return null
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
        else -> null
    }
}
