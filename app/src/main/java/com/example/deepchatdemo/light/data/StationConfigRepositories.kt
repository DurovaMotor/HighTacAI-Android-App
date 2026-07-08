package com.example.deepchatdemo.light.data

import com.example.deepchatdemo.light.domain.StationConfig
import com.example.deepchatdemo.light.protocol.EStationValidators
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

interface StationConfigRepository {
    fun getAll(): List<StationConfig>
    fun get(stationId: String): StationConfig?
    fun upsert(config: StationConfig)
    fun delete(stationId: String): Boolean
    fun clear()
}

class InMemoryStationConfigRepository(
    initialConfigs: Iterable<StationConfig> = emptyList()
) : StationConfigRepository {
    private val lock = Any()
    private val configs = LinkedHashMap<String, StationConfig>()

    init {
        initialConfigs.forEach { configs[it.stationId] = it }
    }

    override fun getAll(): List<StationConfig> = synchronized(lock) {
        configs.values.toList()
    }

    override fun get(stationId: String): StationConfig? = synchronized(lock) {
        EStationValidators.requireStationId(stationId)
        configs[stationId]
    }

    override fun upsert(config: StationConfig) = synchronized(lock) {
        EStationValidators.requireStationId(config.stationId)
        configs[config.stationId] = config
    }

    override fun delete(stationId: String): Boolean = synchronized(lock) {
        EStationValidators.requireStationId(stationId)
        configs.remove(stationId) != null
    }

    override fun clear() = synchronized(lock) {
        configs.clear()
    }
}

class JsonFileStationConfigRepository(
    private val file: File
) : StationConfigRepository {
    private val lock = Any()
    private val configs = LinkedHashMap<String, StationConfig>()

    init {
        readFromDisk().forEach { configs[it.stationId] = it }
    }

    override fun getAll(): List<StationConfig> = synchronized(lock) {
        configs.values.toList()
    }

    override fun get(stationId: String): StationConfig? = synchronized(lock) {
        EStationValidators.requireStationId(stationId)
        configs[stationId]
    }

    override fun upsert(config: StationConfig) = synchronized(lock) {
        EStationValidators.requireStationId(config.stationId)
        configs[config.stationId] = config
        persistLocked()
    }

    override fun delete(stationId: String): Boolean = synchronized(lock) {
        EStationValidators.requireStationId(stationId)
        val removed = configs.remove(stationId) != null
        if (removed) persistLocked()
        removed
    }

    override fun clear() = synchronized(lock) {
        configs.clear()
        persistLocked()
    }

    private fun readFromDisk(): List<StationConfig> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val array = json.optJSONArray("configs") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    runCatching { add(item.toStationConfig()) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistLocked() {
        val json = JSONObject()
            .put("version", FILE_VERSION)
            .put("configs", JSONArray().also { array ->
                configs.values.forEach { array.put(it.toJson()) }
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
            error("Unable to update station config store.")
        }
        if (!tmpFile.renameTo(file)) {
            tmpFile.copyTo(file, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun JSONObject.toStationConfig(): StationConfig {
        return StationConfig(
            stationId = optString("station_id"),
            alias = optionalString("alias").orEmpty(),
            brokerHost = optString("broker_host"),
            brokerPort = optInt("broker_port"),
            username = optString("username"),
            tlsEnabled = optBoolean("tls_enabled")
        )
    }

    private fun StationConfig.toJson(): JSONObject {
        return JSONObject()
            .put("station_id", stationId)
            .put("alias", alias)
            .put("broker_host", brokerHost)
            .put("broker_port", brokerPort)
            .put("username", username)
            .put("tls_enabled", tlsEnabled)
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val FILE_VERSION = 1
    }
}
