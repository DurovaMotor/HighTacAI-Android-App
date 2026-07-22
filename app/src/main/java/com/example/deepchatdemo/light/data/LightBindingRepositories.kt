package com.example.deepchatdemo.light.data

import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.protocol.EStationValidators
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

interface LightBindingRepository {
    fun getAll(): List<LightBinding>
    fun getById(id: String): LightBinding?
    fun getByTagId(tagId: String): LightBinding?
    fun findByItemCode(itemCode: String): List<LightBinding>
    fun upsert(binding: LightBinding)
    fun deleteById(id: String): Boolean
    fun clear()
}

class InMemoryLightBindingRepository(
    initialBindings: Iterable<LightBinding> = emptyList()
) : LightBindingRepository {
    private val lock = Any()
    private val bindings = LinkedHashMap<String, LightBinding>()

    init {
        initialBindings.forEach { bindings[it.id] = it }
    }

    override fun getAll(): List<LightBinding> = synchronized(lock) {
        bindings.values.toList()
    }

    override fun getById(id: String): LightBinding? = synchronized(lock) {
        bindings[id]
    }

    override fun getByTagId(tagId: String): LightBinding? = synchronized(lock) {
        EStationValidators.requireTagId(tagId)
        bindings.values.firstOrNull { it.tagId == tagId }
    }

    override fun findByItemCode(itemCode: String): List<LightBinding> = synchronized(lock) {
        bindings.values.filter { it.itemCode == itemCode }
    }

    override fun upsert(binding: LightBinding) = synchronized(lock) {
        EStationValidators.requireTagId(binding.tagId)
        EStationValidators.requireStationId(binding.stationId)
        bindings[binding.id] = binding
    }

    override fun deleteById(id: String): Boolean = synchronized(lock) {
        bindings.remove(id) != null
    }

    override fun clear() = synchronized(lock) {
        bindings.clear()
    }
}

class JsonFileLightBindingRepository(
    private val file: File
) : LightBindingRepository {
    private val lock = Any()
    private val bindings = LinkedHashMap<String, LightBinding>()

    init {
        readFromDisk().forEach { bindings[it.id] = it }
    }

    override fun getAll(): List<LightBinding> = synchronized(lock) {
        bindings.values.toList()
    }

    override fun getById(id: String): LightBinding? = synchronized(lock) {
        bindings[id]
    }

    override fun getByTagId(tagId: String): LightBinding? = synchronized(lock) {
        EStationValidators.requireTagId(tagId)
        bindings.values.firstOrNull { it.tagId == tagId }
    }

    override fun findByItemCode(itemCode: String): List<LightBinding> = synchronized(lock) {
        bindings.values.filter { it.itemCode == itemCode }
    }

    override fun upsert(binding: LightBinding) = synchronized(lock) {
        EStationValidators.requireTagId(binding.tagId)
        EStationValidators.requireStationId(binding.stationId)
        bindings[binding.id] = binding
        persistLocked()
    }

    override fun deleteById(id: String): Boolean = synchronized(lock) {
        val removed = bindings.remove(id) != null
        if (removed) persistLocked()
        removed
    }

    override fun clear() = synchronized(lock) {
        bindings.clear()
        persistLocked()
    }

    private fun readFromDisk(): List<LightBinding> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.optJSONArray("bindings") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    runCatching { add(item.toLightBinding()) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistLocked() {
        val json = JSONObject()
            .put("version", FILE_VERSION)
            .put("bindings", JSONArray().also { array ->
                bindings.values.forEach { array.put(it.toJson()) }
            })
        writeTextAtomically(json.toString())
    }

    private fun writeTextAtomically(text: String) {
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: File(".")
        val tmpFile = File(parent, "${file.name}.tmp")
        tmpFile.writeText(text, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            tmpFile.delete()
            error("Unable to update light binding store.")
        }
        if (!tmpFile.renameTo(file)) {
            tmpFile.copyTo(file, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun JSONObject.toLightBinding(): LightBinding {
        return LightBinding(
            id = optString("id"),
            itemCode = optString("item_code"),
            itemName = optionalString("item_name"),
            tagId = optString("tag_id"),
            stationId = optString("station_id"),
            shelfCode = optionalString("shelf_code"),
            createdAtMillis = optLong("created_at_millis"),
            updatedAtMillis = optLong("updated_at_millis")
        )
    }

    private fun LightBinding.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("item_code", itemCode)
            .put("item_name", itemName)
            .put("tag_id", tagId)
            .put("station_id", stationId)
            .put("shelf_code", shelfCode)
            .put("created_at_millis", createdAtMillis)
            .put("updated_at_millis", updatedAtMillis)
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val FILE_VERSION = 1
    }
}
