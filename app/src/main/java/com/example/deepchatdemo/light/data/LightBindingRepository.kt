package com.example.deepchatdemo.light.data

import android.content.Context
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.protocol.EStationValidators
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesLightBindingRepository(
    context: Context
) : LightBindingRepository {
    private val preferences = context.applicationContext.getSharedPreferences(
        "light_bindings",
        Context.MODE_PRIVATE
    )

    override fun getAll(): List<LightBinding> {
        val raw = preferences.getString(KEY_BINDINGS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getJSONObject(index).toBinding() }
        }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAtMillis }
    }

    override fun getById(id: String): LightBinding? {
        return getAll().firstOrNull { it.id == id }
    }

    override fun getByTagId(tagId: String): LightBinding? {
        val normalized = EStationValidators.requireTagId(tagId)
        return getAll().firstOrNull { it.normalizedTagId == normalized }
    }

    override fun findByItemCode(itemCode: String): List<LightBinding> {
        val normalized = itemCode.trim().uppercase()
        return getAll().filter { it.normalizedItemCode == normalized }
    }

    override fun upsert(binding: LightBinding) {
        val now = System.currentTimeMillis()
        val normalized = binding.itemCode.trim().uppercase()
        val existing = getAll().firstOrNull { it.normalizedItemCode == normalized }
        val saved = binding.copy(
            id = existing?.id ?: binding.id,
            itemCode = normalized,
            tagId = EStationValidators.requireTagId(binding.tagId),
            stationId = EStationValidators.requireStationId(binding.stationId),
            createdAtMillis = existing?.createdAtMillis ?: binding.createdAtMillis,
            updatedAtMillis = now
        )
        val next = getAll()
            .filterNot { it.id == saved.id || it.normalizedItemCode == normalized }
            .plus(saved)
        save(next)
    }

    override fun deleteById(id: String): Boolean {
        val current = getAll()
        val next = current.filterNot { it.id == id }
        save(next)
        return next.size != current.size
    }

    override fun clear() {
        save(emptyList())
    }

    private fun save(bindings: List<LightBinding>) {
        val array = JSONArray()
        bindings.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_BINDINGS, array.toString()).apply()
    }

    private fun JSONObject.toBinding(): LightBinding {
        return LightBinding(
            id = optString("id"),
            itemCode = optString("itemCode"),
            itemName = optNullableString("itemName"),
            tagId = optString("tagId"),
            stationId = optString("stationId"),
            shelfCode = optNullableString("shelfCode"),
            createdAtMillis = optLong("createdAtMillis"),
            updatedAtMillis = optLong("updatedAtMillis")
        )
    }

    private fun LightBinding.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("itemCode", itemCode)
        .put("itemName", itemName)
        .put("tagId", tagId)
        .put("stationId", stationId)
        .put("shelfCode", shelfCode)
        .put("createdAtMillis", createdAtMillis)
        .put("updatedAtMillis", updatedAtMillis)

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    companion object {
        private const val KEY_BINDINGS = "bindings"
    }
}
