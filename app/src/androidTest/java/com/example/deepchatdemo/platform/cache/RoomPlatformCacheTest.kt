package com.example.deepchatdemo.platform.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPlatformCacheTest {
    private lateinit var database: PlatformCacheDatabase
    private lateinit var cache: RoomPlatformCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            PlatformCacheDatabase::class.java
        ).allowMainThreadQueries().build()
        cache = RoomPlatformCache(database.cacheDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roundTripPreservesModelsAndIsolatesCanonicalEndpoints() = runBlocking {
        val productA = product("A")
        val bindingA = binding("A", productA)
        val tagA = tag("A", bindingA.id)
        val productB = product("B")

        cache.replaceAll(ENDPOINT_A, listOf(productA), listOf(bindingA), listOf(tagA), SYNC_A)
        cache.replaceAll(ENDPOINT_B, listOf(productB), emptyList(), emptyList(), SYNC_B)

        val loadedA = requireNotNull(cache.read(ENDPOINT_A))
        assertEquals(listOf(productA), loadedA.products)
        assertEquals(listOf(bindingA), loadedA.bindings)
        assertEquals(listOf(tagA), loadedA.tags)
        assertEquals(SYNC_A, loadedA.productsSynchronizedAt)
        assertEquals(SYNC_A, loadedA.bindingsSynchronizedAt)
        assertEquals(SYNC_A, loadedA.tagsSynchronizedAt)

        val loadedB = requireNotNull(cache.read(ENDPOINT_B))
        assertEquals(listOf(productB), loadedB.products)
        assertTrue(loadedB.bindings.isEmpty())
        assertTrue(loadedB.tags.isEmpty())
        assertEquals(SYNC_B, loadedB.productsSynchronizedAt)
    }

    @Test
    fun atomicReplacementAcceptsEmptySnapshotAndDropsInactiveBindings() = runBlocking {
        val product = product("A")
        val active = binding("active", product)
        val inactive = binding("inactive", product).copy(
            unboundAt = SYNC_B,
            isActive = false
        )
        cache.replaceAll(
            ENDPOINT_A,
            listOf(product),
            listOf(active, inactive),
            listOf(tag("A", active.id)),
            SYNC_A
        )

        assertEquals(listOf(active), requireNotNull(cache.read(ENDPOINT_A)).bindings)

        cache.replaceAll(ENDPOINT_A, emptyList(), emptyList(), emptyList(), SYNC_B)
        val empty = requireNotNull(cache.read(ENDPOINT_A))
        assertTrue(empty.products.isEmpty())
        assertTrue(empty.bindings.isEmpty())
        assertTrue(empty.tags.isEmpty())
        assertEquals(SYNC_B, empty.productsSynchronizedAt)
        assertEquals(SYNC_B, empty.bindingsSynchronizedAt)
        assertEquals(SYNC_B, empty.tagsSynchronizedAt)
    }

    @Test
    fun confirmedBatchDeltaAtomicallyReplacesTagConflictsForOneEndpoint() = runBlocking {
        val product = product("A")
        val original = binding("original", product)
        val replacement = binding("replacement", product).copy(tagId = original.tagId)
        val otherEndpointBinding = binding("other-endpoint", product)
        cache.replaceAll(ENDPOINT_A, listOf(product), listOf(original), emptyList(), SYNC_A)
        cache.replaceAll(
            ENDPOINT_B,
            listOf(product),
            listOf(otherEndpointBinding),
            emptyList(),
            SYNC_A
        )

        cache.mergeBindings(ENDPOINT_A, listOf(replacement), SYNC_B)

        assertEquals(listOf(replacement), requireNotNull(cache.read(ENDPOINT_A)).bindings)
        assertEquals(
            listOf(otherEndpointBinding),
            requireNotNull(cache.read(ENDPOINT_B)).bindings
        )
        assertEquals(SYNC_B, requireNotNull(cache.read(ENDPOINT_A)).bindingsSynchronizedAt)
    }
}

private fun product(seed: String) = Product(
    id = uuid("product-$seed"),
    productCode = "PRODUCT-$seed",
    productName = "Product $seed",
    source = ProductSource.BINDING,
    isActive = true,
    activeBindingCount = 1,
    createdAt = SYNC_A.minusSeconds(60),
    updatedAt = SYNC_A
)

private fun binding(seed: String, product: Product) = Binding(
    id = uuid("binding-$seed"),
    productId = product.id,
    productCode = product.productCode,
    productName = product.productName,
    tagId = "AD1${seed.padStart(9, '0')}",
    siteId = uuid("site"),
    stationId = "90A9F1234567",
    source = BindingSource.ANDROID,
    actorType = ActorType.ANDROID,
    actorId = "device-$seed",
    actorDisplayName = "Phone $seed",
    boundAt = SYNC_A,
    unboundAt = null,
    isActive = true
)

private fun tag(seed: String, bindingId: UUID) = Tag(
    tagId = "AD1${seed.padStart(9, '0')}",
    siteId = uuid("site"),
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

private fun uuid(seed: String): UUID = UUID.nameUUIDFromBytes(seed.toByteArray())

private const val ENDPOINT_A = "http://192.168.1.105:8088"
private const val ENDPOINT_B = "http://192.168.1.106:8088"
private val SYNC_A = Instant.parse("2026-07-17T01:00:00Z")
private val SYNC_B = Instant.parse("2026-07-17T02:00:00Z")
