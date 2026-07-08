package com.example.deepchatdemo.light.data

import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.light.domain.StationConfig
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightRepositoryTest {
    @Test
    fun inMemoryBindingRepositoryFindsByItemCodeAndTag() {
        val repository = InMemoryLightBindingRepository()
        val binding = sampleBinding()

        repository.upsert(binding)

        assertEquals(binding, repository.getById("binding-1"))
        assertEquals(binding, repository.getByTagId(TAG_ID))
        assertEquals(listOf(binding), repository.findByItemCode("HT-001"))

        repository.deleteById("binding-1")
        assertNull(repository.getById("binding-1"))
    }

    @Test
    fun jsonFileRepositoriesRoundTripBindingsAndStationConfig() {
        withTempDir { dir ->
            val bindingFile = File(dir, "bindings.json")
            JsonFileLightBindingRepository(bindingFile).upsert(sampleBinding())

            val reloadedBindings = JsonFileLightBindingRepository(bindingFile)
            assertEquals(sampleBinding(), reloadedBindings.getByTagId(TAG_ID))

            val configFile = File(dir, "stations.json")
            JsonFileStationConfigRepository(configFile).upsert(sampleStationConfig())

            val reloadedConfigs = JsonFileStationConfigRepository(configFile)
            assertEquals(sampleStationConfig(), reloadedConfigs.get(STATION_ID))
        }
    }
}

private const val STATION_ID = "90A9F1234567"
private const val TAG_ID = "AD100000048F"

private fun sampleBinding(): LightBinding {
    return LightBinding(
        id = "binding-1",
        itemCode = "HT-001",
        itemName = "Drive plate",
        tagId = TAG_ID,
        stationId = STATION_ID,
        shelfCode = "A-01",
        createdAtMillis = 100L,
        updatedAtMillis = 200L
    )
}

private fun sampleStationConfig(): StationConfig {
    return StationConfig(
        stationId = STATION_ID,
        alias = "Dock A",
        brokerHost = "192.168.1.8",
        brokerPort = 1883,
        username = "mqtt_user",
        tlsEnabled = false
    )
}

private fun withTempDir(block: (File) -> Unit) {
    val dir = Files.createTempDirectory("light-repository-test").toFile()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
