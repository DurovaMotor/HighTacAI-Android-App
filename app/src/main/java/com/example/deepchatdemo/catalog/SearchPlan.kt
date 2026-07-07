package com.example.deepchatdemo.catalog

import org.json.JSONArray
import org.json.JSONObject

data class SearchPlan(
    val intent: String = INTENT_UNKNOWN,
    val queryType: String = QUERY_UNKNOWN,
    val rawQuery: String = "",
    val codes: List<String> = emptyList(),
    val codePrefixes: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val brands: List<String> = emptyList(),
    val partNamesCn: List<String> = emptyList(),
    val partNamesEn: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val mustHave: List<String> = emptyList(),
    val shouldHave: List<String> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
    val confidence: Double = 0.0
) {
    val safeLimit: Int
        get() = limit.coerceIn(0, MAX_LIMIT)

    fun isPartsIntent(): Boolean {
        return intent == INTENT_PART_LOOKUP ||
            intent == INTENT_PRICE_LOOKUP ||
            intent == INTENT_MODEL_PARTS_LOOKUP
    }

    fun toLogSummary(): String {
        return "intent=$intent, queryType=$queryType, codes=${codes.size}, " +
            "models=${models.size}, partNames=${partNamesCn.size + partNamesEn.size}, " +
            "limit=$safeLimit, confidence=$confidence"
    }

    companion object {
        const val INTENT_PART_LOOKUP = "part_lookup"
        const val INTENT_PRICE_LOOKUP = "price_lookup"
        const val INTENT_MODEL_PARTS_LOOKUP = "model_parts_lookup"
        const val INTENT_GENERAL_CHAT = "general_chat"
        const val INTENT_UNKNOWN = "unknown"

        const val QUERY_GENERAL = "general"
        const val QUERY_PRICE = "price"
        const val QUERY_CODE = "code"
        const val QUERY_MODEL = "model"
        const val QUERY_PART_NAME = "part_name"
        const val QUERY_BRAND = "brand"
        const val QUERY_UNKNOWN = "unknown"

        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 30

        fun fromJson(text: String): SearchPlan {
            val json = JSONObject(text.extractJsonObject())
            return SearchPlan(
                intent = json.optString("intent", INTENT_UNKNOWN).ifBlank { INTENT_UNKNOWN },
                queryType = json.optString("queryType", QUERY_UNKNOWN).ifBlank { QUERY_UNKNOWN },
                rawQuery = json.optString("rawQuery", ""),
                codes = json.optStringList("codes"),
                codePrefixes = json.optStringList("codePrefixes"),
                models = json.optStringList("models"),
                brands = json.optStringList("brands"),
                partNamesCn = json.optStringList("partNamesCn"),
                partNamesEn = json.optStringList("partNamesEn"),
                keywords = json.optStringList("keywords"),
                mustHave = json.optStringList("mustHave"),
                shouldHave = json.optStringList("shouldHave"),
                limit = json.optInt("limit", DEFAULT_LIMIT).coerceIn(0, MAX_LIMIT),
                confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            )
        }

        fun generalChat(rawQuery: String, confidence: Double = 0.9): SearchPlan {
            return SearchPlan(
                intent = INTENT_GENERAL_CHAT,
                queryType = QUERY_GENERAL,
                rawQuery = rawQuery,
                limit = 0,
                confidence = confidence.coerceIn(0.0, 1.0)
            )
        }
    }
}

private fun JSONObject.optStringList(name: String): List<String> {
    val array: JSONArray = optJSONArray(name) ?: return emptyList()
    val values = mutableListOf<String>()
    for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotBlank() && value !in values) {
            values.add(value)
        }
    }
    return values
}

private fun String.extractJsonObject(): String {
    val withoutFence = trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val start = withoutFence.indexOf('{')
    val end = withoutFence.lastIndexOf('}')
    return if (start >= 0 && end >= start) {
        withoutFence.substring(start, end + 1)
    } else {
        withoutFence
    }
}
