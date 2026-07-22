package com.example.deepchatdemo.platform.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.deepchatdemo.platform.model.ActorType
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingSource
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.ProductSource
import com.example.deepchatdemo.platform.model.Tag
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPlatformCacheRobolectricTest {
    private lateinit var database: PlatformCacheDatabase
    private lateinit var dao: PlatformCacheDao
    private lateinit var cache: RoomPlatformCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            PlatformCacheDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.cacheDao()
        cache = RoomPlatformCache(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun realDaoSnapshotRoundTripPreservesAllCachedModels() = runBlocking {
        assertNull(dao.snapshot(ENDPOINT_A))

        val product = cachedProduct("A")
        val binding = cachedBinding("A", product)
        val tag = cachedTag("A", binding.id)

        cache.replaceAll(
            ENDPOINT_A,
            listOf(product),
            listOf(binding),
            listOf(tag),
            SYNC_A
        )

        val rawSnapshot = dao.snapshot(ENDPOINT_A)
        assertNotNull(rawSnapshot)
        assertEquals(ENDPOINT_A, rawSnapshot?.metadata?.endpoint)
        assertEquals(1, rawSnapshot?.products?.size)
        assertEquals(1, rawSnapshot?.bindings?.size)
        assertEquals(1, rawSnapshot?.tags?.size)

        val loaded = requireNotNull(cache.read(ENDPOINT_A))
        assertEquals(listOf(product), loaded.products)
        assertEquals(listOf(binding), loaded.bindings)
        assertEquals(listOf(tag), loaded.tags)
        assertEquals(SYNC_A, loaded.productsSynchronizedAt)
        assertEquals(SYNC_A, loaded.bindingsSynchronizedAt)
        assertEquals(SYNC_A, loaded.tagsSynchronizedAt)
    }

    @Test
    fun realDaoUpsertsReplaceConflictsOnlyInsideTargetEndpoint() = runBlocking {
        val productA = cachedProduct("A")
        val originalA = cachedBinding("original-A", productA)
        val productB = cachedProduct("B")
        val bindingB = cachedBinding("B", productB).copy(tagId = originalA.tagId)
        cache.replaceAll(ENDPOINT_A, listOf(productA), emptyList(), emptyList(), SYNC_A)
        cache.replaceAll(
            ENDPOINT_B,
            listOf(productB),
            listOf(bindingB),
            listOf(cachedTag("B", bindingB.id).copy(tagId = bindingB.tagId)),
            SYNC_A
        )

        cache.upsertBinding(ENDPOINT_A, originalA, SYNC_A)
        cache.upsertTag(ENDPOINT_A, cachedTag("A", originalA.id).copy(tagId = originalA.tagId), SYNC_A)

        val replacementA = cachedBinding("replacement-A", productA).copy(tagId = originalA.tagId)
        val replacementTagA = cachedTag("replacement-A", replacementA.id).copy(
            tagId = replacementA.tagId
        )
        cache.upsertBinding(ENDPOINT_A, replacementA, SYNC_B)
        cache.upsertTag(ENDPOINT_A, replacementTagA, SYNC_B)

        val loadedA = requireNotNull(cache.read(ENDPOINT_A))
        assertEquals(listOf(replacementA), loadedA.bindings)
        assertEquals(listOf(replacementTagA), loadedA.tags)
        assertEquals(SYNC_B, loadedA.bindingsSynchronizedAt)
        assertEquals(SYNC_B, loadedA.tagsSynchronizedAt)

        val loadedB = requireNotNull(cache.read(ENDPOINT_B))
        assertEquals(listOf(productB), loadedB.products)
        assertEquals(listOf(bindingB), loadedB.bindings)
        assertEquals(bindingB.id, loadedB.tags.single().activeBindingId)
        assertEquals(SYNC_A, loadedB.bindingsSynchronizedAt)
    }

    @Test
    fun emptySnapshotClearsOnlyRequestedEndpoint() = runBlocking {
        val productA = cachedProduct("A")
        val bindingA = cachedBinding("A", productA)
        val productB = cachedProduct("B")
        val bindingB = cachedBinding("B", productB)
        val tagB = cachedTag("B", bindingB.id)
        cache.replaceAll(
            ENDPOINT_A,
            listOf(productA),
            listOf(bindingA),
            listOf(cachedTag("A", bindingA.id)),
            SYNC_A
        )
        cache.replaceAll(ENDPOINT_B, listOf(productB), listOf(bindingB), listOf(tagB), SYNC_A)

        cache.replaceAll(ENDPOINT_A, emptyList(), emptyList(), emptyList(), SYNC_B)

        val rawA = requireNotNull(dao.snapshot(ENDPOINT_A))
        assertTrue(rawA.products.isEmpty())
        assertTrue(rawA.bindings.isEmpty())
        assertTrue(rawA.tags.isEmpty())
        assertEquals(SYNC_B.toEpochMilli(), rawA.metadata.productsSynchronizedAtEpochMillis)
        assertEquals(SYNC_B.toEpochMilli(), rawA.metadata.bindingsSynchronizedAtEpochMillis)
        assertEquals(SYNC_B.toEpochMilli(), rawA.metadata.tagsSynchronizedAtEpochMillis)

        val loadedB = requireNotNull(cache.read(ENDPOINT_B))
        assertEquals(listOf(productB), loadedB.products)
        assertEquals(listOf(bindingB), loadedB.bindings)
        assertEquals(listOf(tagB), loadedB.tags)
        assertEquals(SYNC_A, loadedB.productsSynchronizedAt)
    }
}

private fun cachedProduct(seed: String) = Product(
    id = cachedUuid("product-$seed"),
    productCode = "PRODUCT-$seed",
    productName = "Product $seed",
    source = ProductSource.BINDING,
    isActive = true,
    activeBindingCount = 1,
    createdAt = SYNC_A.minusSeconds(60),
    updatedAt = SYNC_A
)

private fun cachedBinding(seed: String, product: Product) = Binding(
    id = cachedUuid("binding-$seed"),
    productId = product.id,
    productCode = product.productCode,
    productName = product.productName,
    tagId = "AD1${seed.padStart(9, '0')}",
    siteId = cachedUuid("site"),
    stationId = "90A9F1234567",
    source = BindingSource.ANDROID,
    actorType = ActorType.ANDROID,
    actorId = "device-$seed",
    actorDisplayName = "Phone $seed",
    boundAt = SYNC_A,
    unboundAt = null,
    isActive = true
)

private fun cachedTag(seed: String, bindingId: UUID) = Tag(
    tagId = "AD1${seed.padStart(9, '0')}",
    siteId = cachedUuid("site"),
    stationId = "90A9F1234567",
    registeredAt = SYNC_A.minusSeconds(120),
    firstSeenAt = SYNC_A.minusSeconds(90),
    lastSeenAt = SYNC_A,
    online = true,
    batteryRaw = 237,
    batteryVoltage = 2.96,
    batteryLevel = 82,
    lowBattery = false,
    firmwareVersion = "1.0.0",
    groupNo = 3,
    lastResultType = 16,
    isAbnormal = false,
    abnormalReason = null,
    activeBindingId = bindingId
)

private fun cachedUuid(seed: String): UUID = UUID.nameUUIDFromBytes(seed.toByteArray())

private const val ENDPOINT_A = "http://192.168.1.105:8088"
private const val ENDPOINT_B = "http://192.168.1.106:8088"
private val SYNC_A = Instant.parse("2026-07-17T01:00:00Z")
private val SYNC_B = Instant.parse("2026-07-17T02:00:00Z")
