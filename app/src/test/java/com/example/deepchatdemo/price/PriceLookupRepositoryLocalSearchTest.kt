package com.example.deepchatdemo.price

import com.example.deepchatdemo.cloud.DirectCloudResponse
import com.example.deepchatdemo.cloud.JianDaoYunApiRoute
import com.example.deepchatdemo.cloud.JianDaoYunTransport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PriceLookupRepositoryLocalSearchTest {
    @Test
    fun ordinarySearchReadsOnlyThePersistedCache() = runBlocking {
        val directory = Files.createTempDirectory("price-local-search").toFile()
        try {
            val cacheStore = PriceLookupCacheStore(File(directory, "cache.json"))
            cacheStore.save(
                PriceLookupCachedSnapshot(
                    entryId = PriceLookupSeedImporter.cacheEntryId(),
                    entryName = "产品信息",
                    results = listOf(
                        PriceLookupResult(
                            id = "row-1",
                            code = "ABC-001",
                            nameCn = "测试配件",
                            searchValues = mapOf(PriceLookupColumns.CODE to "ABC-001")
                        )
                    ),
                    pageCount = 1,
                    fetchedRowCount = 1,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
            val transport = FailingIfCalledJianDaoYunTransport()
            val repository = PriceLookupRepository(
                jiandaoYunPriceApi = JianDaoYunPriceApi(transport, cacheStore),
                cacheStore = cacheStore
            )

            val result = repository.lookup(
                filters = listOf(
                    PriceFilterCondition(
                        id = "filter",
                        columnKey = PriceLookupColumns.CODE,
                        columnLabel = "编码",
                        value = "abc001"
                    )
                ),
                forceRefresh = false
            )

            assertTrue(result.fromCache)
            assertEquals("ABC-001", result.results.single().code)
            assertEquals(0, transport.calls)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private class FailingIfCalledJianDaoYunTransport : JianDaoYunTransport {
    var calls: Int = 0

    override fun postJson(
        route: JianDaoYunApiRoute,
        jsonBody: String
    ): DirectCloudResponse {
        calls += 1
        error("Ordinary local search must not call JianDaoYun: $route")
    }
}
