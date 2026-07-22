package com.example.deepchatdemo.price

import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.config.PlatformUrlValidator
import com.example.deepchatdemo.platform.network.PlatformImageUrlResolver
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PriceLookupCacheStoreTest {
    @Test
    fun savesAndLoadsSnapshotWithSearchValues() {
        withTempCache { cacheFile ->
            val store = PriceLookupCacheStore(cacheFile, imageUrlResolver())
            val snapshot = PriceLookupCachedSnapshot(
                entryId = "entry-a",
                entryName = "产品信息",
                results = listOf(
                    PriceLookupResult(
                        id = "row-1",
                        imageUrl = "/api/v1/mobile/media/cache-token",
                        code = "ABC-001",
                        nameCn = "驱动盘",
                        models = "SYMPHONY ST",
                        latestPrice = "12.5",
                        fieldValues = mapOf(PriceLookupColumns.NAME_CN to "驱动盘"),
                        searchValues = mapOf(
                            PriceLookupColumns.NAME_CN to "驱动盘 / Drive Plate",
                            PriceLookupColumns.MODELS to "SYMPHONY ST"
                        ),
                        rawDetails = listOf("产品中文名称" to "驱动盘")
                    )
                ),
                pageCount = 73,
                fetchedRowCount = 7230,
                createdAtEpochMs = 123456789L
            )

            store.save(snapshot)

            val loaded = store.load("entry-a")
            assertNotNull(loaded)
            requireNotNull(loaded)
            assertEquals("产品信息", loaded.entryName)
            assertEquals(73, loaded.pageCount)
            assertEquals(7230, loaded.fetchedRowCount)
            assertEquals("驱动盘", loaded.results.single().nameCn)
            assertEquals("", loaded.results.single().imageUrl)
            assertFalse(cacheFile.readText(Charsets.UTF_8).contains("image_url"))
            assertFalse(cacheFile.readText(Charsets.UTF_8).contains("cache-token"))
            assertEquals("SYMPHONY ST", loaded.results.single().searchValues[PriceLookupColumns.MODELS])
            assertEquals("产品中文名称" to "驱动盘", loaded.results.single().rawDetails.single())
        }
    }

    @Test
    fun loadIgnoresSnapshotFromDifferentEntry() {
        withTempCache { cacheFile ->
            val store = PriceLookupCacheStore(cacheFile, imageUrlResolver())
            store.save(
                PriceLookupCachedSnapshot(
                    entryId = "entry-a",
                    entryName = "产品信息",
                    results = emptyList(),
                    pageCount = 0,
                    fetchedRowCount = 0,
                    createdAtEpochMs = 123456789L
                )
            )

            assertNull(store.load("entry-b"))
        }
    }

    @Test
    fun loadPurgesLegacyThirdPartyImageUrlsFromThePersistedCache() {
        withTempCache { cacheFile ->
            cacheFile.writeText(
                """
                    {
                      "version": 1,
                      "entry_id": "entry-a",
                      "entry_name": "产品信息",
                      "page_count": 1,
                      "fetched_row_count": 1,
                      "created_at_epoch_ms": 123456789,
                      "results": [
                        {
                          "id": "row-1",
                          "image_url": "https://files.jiandaoyun.com/legacy-image"
                        }
                      ]
                    }
                """.trimIndent(),
                Charsets.UTF_8
            )
            val store = PriceLookupCacheStore(cacheFile, imageUrlResolver())

            val loaded = requireNotNull(store.load("entry-a"))

            assertEquals("", loaded.results.single().imageUrl)
            assertFalse(
                cacheFile.readText(Charsets.UTF_8)
                    .contains("files.jiandaoyun.com", ignoreCase = true)
            )
        }
    }

    @Test
    fun loadPurgesLegacyPlatformMediaTokensFromThePersistedCache() {
        withTempCache { cacheFile ->
            cacheFile.writeText(
                """
                    {
                      "version": 1,
                      "entry_id": "entry-a",
                      "entry_name": "产品信息",
                      "page_count": 1,
                      "fetched_row_count": 1,
                      "created_at_epoch_ms": 123456789,
                      "results": [
                        {
                          "id": "row-1",
                          "image_url": "http://192.168.1.105:8088/api/v1/mobile/media/expired-token"
                        }
                      ]
                    }
                """.trimIndent(),
                Charsets.UTF_8
            )
            val store = PriceLookupCacheStore(cacheFile, imageUrlResolver())

            val loaded = requireNotNull(store.load("entry-a"))

            assertEquals("", loaded.results.single().imageUrl)
            val persisted = cacheFile.readText(Charsets.UTF_8)
            assertFalse(persisted.contains("image_url"))
            assertFalse(persisted.contains("expired-token"))
        }
    }

    private fun imageUrlResolver(): PlatformImageUrlResolver {
        val endpoint = PlatformUrlValidator.requireForWifiProduction(
            "http://192.168.1.105:8088"
        )
        return PlatformImageUrlResolver(PlatformEndpointProvider { endpoint })
    }

    private fun withTempCache(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("price-cache-test").toFile()
        try {
            block(File(dir, "cache.json"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
