package com.example.deepchatdemo.platform.repository

import com.example.deepchatdemo.platform.cache.CachedPlatformSnapshot
import com.example.deepchatdemo.platform.cache.PlatformCache
import com.example.deepchatdemo.platform.config.PlatformEndpoint
import com.example.deepchatdemo.platform.config.PlatformEndpointChangedException
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.config.PlatformUrlValidator
import com.example.deepchatdemo.platform.model.ActorType
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationClassification
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRecord
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitResult
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitSummary
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationOutcome
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationRecord
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingCreateRequest
import com.example.deepchatdemo.platform.model.BindingSource
import com.example.deepchatdemo.platform.model.BrokerServiceState
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.CheckStatus
import com.example.deepchatdemo.platform.model.EventEntityType
import com.example.deepchatdemo.platform.model.Page
import com.example.deepchatdemo.platform.model.Pagination
import com.example.deepchatdemo.platform.model.PlatformEvent
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.ProductSource
import com.example.deepchatdemo.platform.model.Readiness
import com.example.deepchatdemo.platform.model.ReadinessCheck
import com.example.deepchatdemo.platform.model.ReadinessCheckName
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.StationStatus
import com.example.deepchatdemo.platform.model.StationStatusChangedEvent
import com.example.deepchatdemo.platform.model.StationStatusChangedPayload
import com.example.deepchatdemo.platform.model.Tag
import com.example.deepchatdemo.platform.network.HighTacPlatformApi
import com.example.deepchatdemo.platform.network.IdempotencyKey
import com.example.deepchatdemo.platform.network.IdempotencyKeyFactory
import com.example.deepchatdemo.platform.network.PlatformEventConnectionState
import com.example.deepchatdemo.platform.network.PlatformEventFailure
import com.example.deepchatdemo.platform.network.PlatformEventStream
import com.example.deepchatdemo.platform.network.PlatformTransportException
import com.example.deepchatdemo.platform.network.SnapshotRefreshRequired
import com.example.deepchatdemo.platform.security.PlatformCredentialStore
import com.example.deepchatdemo.platform.security.StoredEnrollment
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlatformRepositoryTest {
    @Test
    fun startupHydratesCachedProductsAsStaleWithoutAuthorizingWrites() = runBlocking {
        val oldProduct = cachedProduct("old")
        val cache = FakePlatformCache().apply {
            snapshots[CACHE_ENDPOINT_A] = cachedSnapshot(
                endpoint = CACHE_ENDPOINT_A,
                products = listOf(oldProduct)
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = apiProxy { throw AssertionError("Cache hydration must not call the API.") },
            credentials = FakeCredentialStore(hasToken = false),
            eventStream = FakeEventStream(),
            endpointProvider = MutableEndpointProvider(CACHE_ENDPOINT_A),
            scope = scope,
            cache = cache
        )

        assertEquals(listOf(oldProduct), repository.products.value.value)
        assertEquals(CACHE_SYNC, repository.products.value.synchronizedAt)
        assertTrue(repository.products.value.isStale)
        assertFalse(repository.accessState.value.canWrite)
        repository.close()
        scope.cancel()
    }

    @Test
    fun incompletePaginationPreservesHydratedCacheAndDoesNotReplaceRows() = runBlocking {
        val oldProduct = cachedProduct("old")
        val cache = FakePlatformCache().apply {
            snapshots[CACHE_ENDPOINT_A] = cachedSnapshot(
                endpoint = CACHE_ENDPOINT_A,
                products = listOf(oldProduct)
            )
        }
        var productPageCalls = 0
        val api = apiProxy { methodName ->
            if (methodName != "listProducts") error("Unexpected API call: $methodName")
            productPageCalls += 1
            if (productPageCalls == 2) {
                throw PlatformTransportException(IOException("page 2 failed"))
            }
            Page(
                items = List(100) { cachedProduct("remote-$it") },
                pagination = Pagination(
                    page = 1,
                    pageSize = 100,
                    totalItems = 101,
                    totalPages = 2
                )
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = api,
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = FakeEventStream(),
            endpointProvider = MutableEndpointProvider(CACHE_ENDPOINT_A),
            scope = scope,
            cache = cache
        )

        try {
            repository.refreshProducts()
        } catch (_: PlatformTransportException) {
            Unit
        }

        assertEquals(2, productPageCalls)
        assertEquals(listOf(oldProduct), repository.products.value.value)
        assertEquals(listOf(oldProduct), cache.snapshots[CACHE_ENDPOINT_A]?.products)
        assertTrue(cache.productReplacements.isEmpty())
        repository.close()
        scope.cancel()
    }

    @Test
    fun endpointGenerationRejectsResponseReturnedAfterEndpointSwitch() = runBlocking {
        val endpointProvider = MutableEndpointProvider(CACHE_ENDPOINT_A)
        val endpointBProduct = cachedProduct("endpoint-b")
        val cache = FakePlatformCache().apply {
            snapshots[CACHE_ENDPOINT_A] = cachedSnapshot(
                CACHE_ENDPOINT_A,
                listOf(cachedProduct("endpoint-a"))
            )
            snapshots[CACHE_ENDPOINT_B] = cachedSnapshot(
                CACHE_ENDPOINT_B,
                listOf(endpointBProduct)
            )
        }
        val lateProduct = cachedProduct("late-a")
        val api = apiProxy { methodName ->
            if (methodName != "listProducts") error("Unexpected API call: $methodName")
            endpointProvider.switchTo(CACHE_ENDPOINT_B)
            page(listOf(lateProduct))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = api,
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = FakeEventStream(),
            endpointProvider = endpointProvider,
            scope = scope,
            cache = cache
        )

        try {
            repository.refreshProducts()
        } catch (_: PlatformEndpointChangedException) {
            Unit
        }

        assertEquals(listOf(endpointBProduct), repository.products.value.value)
        assertTrue(repository.products.value.isStale)
        assertTrue(cache.productReplacements.isEmpty())
        repository.close()
        scope.cancel()
    }

    @Test
    fun storedTokenDoesNotOpenWritesBeforeAuthenticatedVerification() = runBlocking {
        var apiCalls = 0
        val api = apiProxy { methodName ->
            apiCalls += 1
            throw AssertionError("API must not be called while writes are closed: $methodName")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = api,
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = FakeEventStream(),
            endpointProvider = TEST_ENDPOINT_PROVIDER,
            scope = scope
        )

        val error = expectWriteClosed {
            repository.bind(validBindingRequest())
        }

        assertEquals(PlatformWriteClosedReason.DEVICE_NOT_APPROVED, error.reason)
        assertFalse(repository.accessState.value.canWrite)
        assertEquals(0, apiCalls)
        scope.cancel()
    }

    @Test
    fun transportFailureClosesSubsequentWritesWithoutCallingWriteApi() = runBlocking {
        val calls = mutableListOf<String>()
        val api = apiProxy { methodName ->
            calls += methodName
            throw PlatformTransportException(IOException("offline"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = api,
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = FakeEventStream(),
            endpointProvider = TEST_ENDPOINT_PROVIDER,
            scope = scope
        )

        try {
            repository.refreshAll()
        } catch (_: PlatformTransportException) {
            Unit
        }
        val error = expectWriteClosed {
            repository.bind(validBindingRequest())
        }

        assertEquals(PlatformApiAvailability.UNAVAILABLE, repository.accessState.value.apiAvailability)
        assertEquals(PlatformWriteClosedReason.API_UNAVAILABLE, error.reason)
        assertEquals(listOf("getReadiness"), calls)
        scope.cancel()
    }

    @Test
    fun mqttNotReadyDoesNotMakeReachableApiUnavailable() = runBlocking {
        val calls = mutableListOf<String>()
        val api = snapshotApi(
            readiness = readiness(mqtt = CheckStatus.NOT_READY),
            brokerStatus = brokerStatus(mqttConnected = false),
            stations = listOf(station())
        ) { calls += it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = api,
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = FakeEventStream(),
            endpointProvider = TEST_ENDPOINT_PROVIDER,
            scope = scope
        )

        repository.refreshAll()

        assertTrue(repository.accessState.value.isApiReachable)
        assertTrue(repository.accessState.value.isDeviceApproved)
        assertTrue(repository.accessState.value.canWrite)
        assertFalse(requireNotNull(repository.brokerStatus.value.value).isReady)
        assertEquals(
            listOf(
                "getReadiness",
                "getBrokerStatus",
                "listProducts",
                "listBindings",
                "listTags",
                "listStations"
            ),
            calls
        )
        scope.cancel()
    }

    @Test
    fun successfulAtomicMigrationCommitMergesConfirmedBindingsIntoEndpointCache() = runBlocking {
        val record = AndroidBindingMigrationRecord(
            clientRecordKey = "legacy-1",
            productCode = "PRODUCT-A",
            productName = "Product A",
            tagId = "AD100000048F",
            stationId = STATION_ID
        )
        val created = migrationBinding(record)
        val committed = AndroidBindingMigrationCommitResult(
            committedAt = NOW,
            summary = AndroidBindingMigrationCommitSummary(
                totalRecords = 1,
                migratedRecords = 1,
                identicalRecords = 0,
                skippedRecords = 0,
                createdProducts = 1,
                createdTags = 1,
                createdBindings = 1
            ),
            records = listOf(
                AndroidBindingMigrationCommitRecord(
                    clientRecordKey = record.clientRecordKey,
                    classification = AndroidBindingMigrationClassification.MIGRATABLE,
                    outcome = AndroidBindingMigrationOutcome.MIGRATED,
                    bindingId = created.id
                )
            ),
            bindings = listOf(created)
        )
        val cache = FakePlatformCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = snapshotApi(migrationCommit = committed),
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = FakeEventStream(),
            endpointProvider = TEST_ENDPOINT_PROVIDER,
            scope = scope,
            cache = cache
        )
        repository.refreshAll()

        repository.commitAndroidBindingMigration(
            request = AndroidBindingMigrationCommitRequest(
                records = listOf(record),
                previewToken = "${"a".repeat(32)}.${"b".repeat(43)}",
                selectedDuplicateKeys = emptyList()
            ),
            idempotencyKey = IdempotencyKey.parse("2ec5fd45-0e4d-4be4-a6bd-3517118b64df")
        )

        assertEquals(listOf(created), repository.bindings.value.value)
        assertEquals(CACHE_ENDPOINT_A, cache.bindingMerges.single().first)
        assertEquals(listOf(created), cache.bindingMerges.single().second)
        assertEquals(listOf(created), cache.snapshots[CACHE_ENDPOINT_A]?.bindings)
        assertTrue(repository.products.value.isStale)
        assertTrue(repository.tags.value.isStale)
        repository.close()
        scope.cancel()
    }

    @Test
    fun realtimeStationStatusEventUpdatesKnownStationImmediately() = runBlocking {
        val eventStream = FakeEventStream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = snapshotApi(stations = listOf(station())),
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = eventStream,
            endpointProvider = TEST_ENDPOINT_PROVIDER,
            scope = scope
        )
        repository.startRealtime()

        eventStream.emit(
            StationStatusChangedEvent(
                eventId = UUID.randomUUID(),
                schemaVersion = 1,
                occurredAt = NOW.plusSeconds(5),
                entityType = EventEntityType.STATION,
                entityId = STATION_ID,
                payload = StationStatusChangedPayload(
                    stationId = STATION_ID,
                    previousStatus = StationStatus.ONLINE,
                    currentStatus = StationStatus.OFFLINE,
                    lastHeartbeatAt = NOW,
                    brokerConnected = false,
                    reason = "heartbeat_timeout"
                )
            )
        )

        val updated = repository.stations.value.value.single()
        assertEquals(StationStatus.OFFLINE, updated.status)
        assertFalse(updated.brokerConnected)
        repository.stopRealtime()
        scope.cancel()
    }

    @Test
    fun activeStationRefreshStartsImmediatelyAndIsIdempotent() = runBlocking {
        var stationCalls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RemotePlatformRepository(
            api = snapshotApi(stations = listOf(station())) { methodName ->
                if (methodName == "listStations") stationCalls += 1
            },
            credentials = FakeCredentialStore(hasToken = true),
            eventStream = FakeEventStream(),
            endpointProvider = TEST_ENDPOINT_PROVIDER,
            scope = scope,
            stationRefreshSleeper = { awaitCancellation() }
        )
        repository.startRealtime()
        val callsAfterInitialSnapshot = stationCalls

        repository.setStationStatusRefreshActive(true)
        repository.setStationStatusRefreshActive(true)

        assertEquals(callsAfterInitialSnapshot + 1, stationCalls)
        repository.setStationStatusRefreshActive(false)
        repository.stopRealtime()
        scope.cancel()
    }

    private fun validBindingRequest() = BindingCreateRequest(
        productCode = "1711A-ABA-PT",
        productName = null,
        tagId = "AD100000048F",
        stationId = "90A9F1234567"
    )
}

private suspend fun expectWriteClosed(block: suspend () -> Unit): PlatformWriteClosedException {
    try {
        block()
    } catch (error: PlatformWriteClosedException) {
        return error
    }
    throw AssertionError("Expected a fail-closed platform write.")
}

private fun apiProxy(onCall: (String) -> Any?): HighTacPlatformApi =
    Proxy.newProxyInstance(
        HighTacPlatformApi::class.java.classLoader,
        arrayOf(HighTacPlatformApi::class.java)
    ) { _, method, arguments ->
        try {
            onCall(method.name)
        } catch (error: Throwable) {
            @Suppress("UNCHECKED_CAST")
            val continuation = arguments?.lastOrNull() as? Continuation<Any?>
            if (continuation != null) {
                continuation.resumeWith(Result.failure(error))
                COROUTINE_SUSPENDED
            } else {
                throw error
            }
        }
    } as HighTacPlatformApi

private fun snapshotApi(
    readiness: Readiness = readiness(),
    brokerStatus: BrokerStatus = brokerStatus(),
    stations: List<Station> = emptyList(),
    migrationCommit: AndroidBindingMigrationCommitResult? = null,
    onCall: (String) -> Unit = {}
): HighTacPlatformApi = apiProxy { methodName ->
    onCall(methodName)
    when (methodName) {
        "getReadiness" -> readiness
        "getBrokerStatus" -> brokerStatus
        "listProducts",
        "listBindings",
        "listTags" -> emptyPage<Any>()
        "listStations" -> page(stations)
        else -> if (methodName.startsWith("commitAndroidBindingMigration")) {
            requireNotNull(migrationCommit)
        } else {
            throw AssertionError("Unexpected API call: $methodName")
        }
    }
}

private fun readiness(
    database: CheckStatus = CheckStatus.READY,
    migrations: CheckStatus = CheckStatus.READY,
    mqtt: CheckStatus = CheckStatus.READY
) = Readiness(
    status = if (listOf(database, migrations, mqtt).any { it == CheckStatus.NOT_READY }) {
        CheckStatus.NOT_READY
    } else {
        CheckStatus.READY
    },
    checks = listOf(
        readinessCheck(ReadinessCheckName.DATABASE, database),
        readinessCheck(ReadinessCheckName.MIGRATIONS, migrations),
        readinessCheck(ReadinessCheckName.MQTT_BRIDGE, mqtt)
    ),
    checkedAt = NOW
)

private fun readinessCheck(name: ReadinessCheckName, status: CheckStatus) = ReadinessCheck(
    name = name,
    status = status,
    message = "$name is $status",
    checkedAt = NOW
)

private fun brokerStatus(mqttConnected: Boolean = true) = BrokerStatus(
    serviceName = "HighTacMqttBroker",
    serviceState = BrokerServiceState.RUNNING,
    endpoint = "192.168.1.105:1884",
    tcpReachable = true,
    mqttConnected = mqttConnected,
    subscriptionsReady = mqttConnected,
    startedAt = NOW.minusSeconds(60),
    uptimeSeconds = 60,
    checkedAt = NOW
)

private fun station() = Station(
    stationId = STATION_ID,
    siteId = UUID.nameUUIDFromBytes("site".toByteArray()),
    alias = "Receiving",
    status = StationStatus.ONLINE,
    mac = "90:A9:F1:23:45:67",
    firmwareVersion = "1.0.0",
    serverAddress = "192.168.1.105:1884",
    heartbeatSeconds = 20,
    lastHeartbeatAt = NOW,
    totalCount = 10,
    sendCount = 1,
    brokerConnected = true,
    createdAt = NOW.minusSeconds(3_600),
    updatedAt = NOW
)

private fun <T> emptyPage(): Page<T> = page(emptyList())

private fun <T> page(items: List<T>) = Page(
    items = items,
    pagination = Pagination(
        page = 1,
        pageSize = 100,
        totalItems = items.size.toLong(),
        totalPages = if (items.isEmpty()) 0 else 1
    )
)

private class FakeCredentialStore(hasToken: Boolean) : PlatformCredentialStore {
    private var token = if (hasToken) SensitiveString.fromTransport("approved-device-token") else null

    override fun installationKeyHash(): String = "ab".repeat(32)
    override fun deviceId(): UUID? = null
    override fun pendingEnrollment(): StoredEnrollment? = null
    override fun savePendingEnrollment(id: UUID, pollSecret: SensitiveString) = Unit
    override fun clearPendingEnrollment() = Unit
    override fun deviceToken(): SensitiveString? = token
    override fun saveDeviceToken(deviceId: UUID, token: SensitiveString) {
        this.token = token
    }
    override fun clearDeviceToken() {
        token = null
    }
    override fun enrollmentIdempotencyKey(factory: IdempotencyKeyFactory): IdempotencyKey =
        factory.create()
    override fun clearEnrollmentIdempotencyKey() = Unit
}

private class FakeEventStream : PlatformEventStream {
    private val mutableEvents = MutableSharedFlow<PlatformEvent>()

    override val connectionState: StateFlow<PlatformEventConnectionState> =
        MutableStateFlow(PlatformEventConnectionState.Stopped)
    override val events: SharedFlow<PlatformEvent> = mutableEvents
    override val failures: SharedFlow<PlatformEventFailure> = MutableSharedFlow()
    override val snapshotRefreshGeneration: StateFlow<Long> = MutableStateFlow(0L)
    override val snapshotRefreshSignals: SharedFlow<SnapshotRefreshRequired> = MutableSharedFlow()

    override fun connect() = Unit
    override fun disconnect() = Unit

    suspend fun emit(event: PlatformEvent) {
        mutableEvents.emit(event)
    }
}

private class MutableEndpointProvider(initialUrl: String) : PlatformEndpointProvider {
    private var endpoint = PlatformUrlValidator.requireForWifiProduction(initialUrl)
    private val listeners = linkedSetOf<(PlatformEndpoint) -> Unit>()

    override fun currentEndpoint(): PlatformEndpoint = endpoint

    override fun addEndpointChangeListener(
        listener: (PlatformEndpoint) -> Unit
    ): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun switchTo(url: String) {
        endpoint = PlatformUrlValidator.requireForWifiProduction(url)
        listeners.toList().forEach { it(endpoint) }
    }
}

private class FakePlatformCache : PlatformCache {
    val snapshots = mutableMapOf<String, CachedPlatformSnapshot>()
    val productReplacements = mutableListOf<Pair<String, List<Product>>>()
    val bindingMerges = mutableListOf<Pair<String, List<Binding>>>()

    override suspend fun read(endpoint: String): CachedPlatformSnapshot? = snapshots[endpoint]

    override suspend fun replaceAll(
        endpoint: String,
        products: List<Product>,
        bindings: List<Binding>,
        tags: List<Tag>,
        synchronizedAt: Instant
    ) {
        snapshots[endpoint] = CachedPlatformSnapshot(
            endpoint,
            products,
            bindings.filter(Binding::isActive),
            tags,
            synchronizedAt,
            synchronizedAt,
            synchronizedAt
        )
    }

    override suspend fun replaceProducts(
        endpoint: String,
        products: List<Product>,
        synchronizedAt: Instant
    ) {
        productReplacements += endpoint to products
        val current = snapshots[endpoint] ?: cachedSnapshot(endpoint, emptyList())
        snapshots[endpoint] = current.copy(
            products = products,
            productsSynchronizedAt = synchronizedAt
        )
    }

    override suspend fun replaceBindings(
        endpoint: String,
        bindings: List<Binding>,
        synchronizedAt: Instant
    ) = Unit

    override suspend fun replaceTags(
        endpoint: String,
        tags: List<Tag>,
        synchronizedAt: Instant
    ) = Unit

    override suspend fun upsertBinding(
        endpoint: String,
        binding: Binding,
        synchronizedAt: Instant
    ) = Unit

    override suspend fun mergeBindings(
        endpoint: String,
        bindings: List<Binding>,
        synchronizedAt: Instant
    ) {
        bindingMerges += endpoint to bindings
        val current = snapshots[endpoint] ?: cachedSnapshot(endpoint, emptyList())
        val ids = bindings.map { it.id }.toSet()
        val tags = bindings.map { it.tagId }.toSet()
        snapshots[endpoint] = current.copy(
            bindings = current.bindings
                .filterNot { it.id in ids || it.tagId in tags }
                .plus(bindings),
            bindingsSynchronizedAt = synchronizedAt
        )
    }

    override suspend fun replaceBinding(
        endpoint: String,
        removedBindingId: UUID,
        createdBinding: Binding,
        synchronizedAt: Instant
    ) = Unit

    override suspend fun removeBinding(
        endpoint: String,
        bindingId: UUID,
        synchronizedAt: Instant
    ) = Unit

    override suspend fun upsertTag(endpoint: String, tag: Tag, synchronizedAt: Instant) = Unit
}

private fun cachedSnapshot(
    endpoint: String,
    products: List<Product>
) = CachedPlatformSnapshot(
    endpoint = endpoint,
    products = products,
    bindings = emptyList(),
    tags = emptyList(),
    productsSynchronizedAt = CACHE_SYNC,
    bindingsSynchronizedAt = CACHE_SYNC,
    tagsSynchronizedAt = CACHE_SYNC
)

private fun cachedProduct(seed: String) = Product(
    id = UUID.nameUUIDFromBytes(seed.toByteArray()),
    productCode = "PRODUCT-${seed.uppercase()}",
    productName = seed,
    source = ProductSource.BINDING,
    isActive = true,
    activeBindingCount = 0,
    createdAt = CACHE_SYNC.minusSeconds(60),
    updatedAt = CACHE_SYNC
)

private fun migrationBinding(record: AndroidBindingMigrationRecord) = Binding(
    id = UUID.fromString("fcf421af-1b4d-4919-8273-7fda9067f6dc"),
    productId = UUID.fromString("a4087334-ce5d-4afb-9207-ddc3a867bf95"),
    productCode = record.productCode,
    productName = record.productName,
    tagId = record.tagId,
    siteId = UUID.fromString("2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68"),
    stationId = record.stationId,
    source = BindingSource.MIGRATION,
    actorType = ActorType.ANDROID,
    actorId = "android-device",
    actorDisplayName = "Receiving Phone",
    boundAt = NOW,
    unboundAt = null,
    isActive = true
)

private val NOW: Instant = Instant.parse("2026-07-16T08:00:00Z")
private val CACHE_SYNC: Instant = Instant.parse("2026-07-16T07:00:00Z")
private const val STATION_ID = "90A9F1234567"
private const val CACHE_ENDPOINT_A = "http://192.168.1.105:8088"
private const val CACHE_ENDPOINT_B = "http://192.168.1.106:8088"
private val TEST_ENDPOINT_PROVIDER = PlatformEndpointProvider {
    PlatformUrlValidator.requireForWifiProduction(PlatformUrlValidator.DEFAULT_SERVER_URL)
}
