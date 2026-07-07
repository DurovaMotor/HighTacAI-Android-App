package com.example.deepchatdemo.price

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PriceLookupCacheStoreTest {
    @Test
    fun savesAndLoadsSnapshotWithSearchValues() {
        withTempCache { cacheFile ->
            val store = PriceLookupCacheStore(cacheFile)
            val snapshot = PriceLookupCachedSnapshot(
                entryId = "entry-a",
                entryName = "产品信息",
                results = listOf(
                    PriceLookupResult(
                        id = "row-1",
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
            assertEquals("SYMPHONY ST", loaded.results.single().searchValues[PriceLookupColumns.MODELS])
            assertEquals("产品中文名称" to "驱动盘", loaded.results.single().rawDetails.single())
        }
    }

    @Test
    fun loadIgnoresSnapshotFromDifferentEntry() {
        withTempCache { cacheFile ->
            val store = PriceLookupCacheStore(cacheFile)
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

    private fun withTempCache(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("price-cache-test").toFile()
        try {
            block(File(dir, "cache.json"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
