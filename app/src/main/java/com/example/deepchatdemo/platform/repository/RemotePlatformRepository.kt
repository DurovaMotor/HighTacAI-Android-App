package com.example.deepchatdemo.platform.repository

import com.example.deepchatdemo.platform.cache.NoOpPlatformCache
import com.example.deepchatdemo.platform.cache.PlatformCache
import com.example.deepchatdemo.platform.config.PlatformEndpoint
import com.example.deepchatdemo.platform.config.PlatformEndpointChangedException
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.json.PlatformValueRules
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitResult
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreview
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRequest
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingCreateRequest
import com.example.deepchatdemo.platform.model.BindingCreatedEvent
import com.example.deepchatdemo.platform.model.BindingRemovedEvent
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.BrokerStatusChangedEvent
import com.example.deepchatdemo.platform.model.CheckStatus
import com.example.deepchatdemo.platform.model.CommandItemStatus
import com.example.deepchatdemo.platform.model.CommandStatusChangedEvent
import com.example.deepchatdemo.platform.model.DeviceStatus
import com.example.deepchatdemo.platform.model.DeviceStatusChangedEvent
import com.example.deepchatdemo.platform.model.LightCommand
import com.example.deepchatdemo.platform.model.LightCommandRequest
import com.example.deepchatdemo.platform.model.Page
import com.example.deepchatdemo.platform.model.PageRequest
import com.example.deepchatdemo.platform.model.PlatformEvent
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.RebindRequest
import com.example.deepchatdemo.platform.model.RebindResult
import com.example.deepchatdemo.platform.model.ReadinessCheckName
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.StationHeartbeatEvent
import com.example.deepchatdemo.platform.model.StationStatusChangedEvent
import com.example.deepchatdemo.platform.model.Tag
import com.example.deepchatdemo.platform.model.TagRegisterRequest
import com.example.deepchatdemo.platform.model.TagStatusChangedEvent
import com.example.deepchatdemo.platform.network.HighTacPlatformApi
import com.example.deepchatdemo.platform.network.IdempotencyKeyFactory
import com.example.deepchatdemo.platform.network.IdempotencyKey
import com.example.deepchatdemo.platform.network.PlatformApiException
import com.example.deepchatdemo.platform.network.PlatformAuthenticationException
import com.example.deepchatdemo.platform.network.PlatformEventStream
import com.example.deepchatdemo.platform.network.PlatformEventConnectionState
import com.example.deepchatdemo.platform.network.PlatformProtocolException
import com.example.deepchatdemo.platform.network.PlatformTransportException
import com.example.deepchatdemo.platform.security.PlatformCredentialStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemotePlatformRepository(
    private val api: HighTacPlatformApi,
    private val credentials: PlatformCredentialStore,
    private val eventStream: PlatformEventStream,
    private val endpointProvider: PlatformEndpointProvider,
    private val scope: CoroutineScope,
    private val cache: PlatformCache = NoOpPlatformCache,
    private val idempotencyKeys: IdempotencyKeyFactory = IdempotencyKeyFactory(),
    private val now: () -> Instant = Instant::now,
    private val stationRefreshSleeper: suspend (Long) -> Unit = { delay(it) }
) : PlatformRepository, AutoCloseable {
    private val stateMutationMutex = Mutex()
    private val endpointLock = Any()
    private val lifecycleLock = Any()
    private val commandLock = Any()
    private val observerJobs = mutableListOf<Job>()
    private val pendingCommandEvents = linkedMapOf<UUID, MutableList<CommandStatusChangedEvent>>()
    private var bindingRefreshJob: Job? = null
    private var initialRefreshJob: Job? = null
    private var stationEventRefreshJob: Job? = null
    private var stationStatusRefreshJob: Job? = null
    private var cacheHydrationJob: Job? = null
    private var realtimeStarted = false
    private var stationStatusRefreshRequested = false
    private var endpointGeneration = 0L
    private var endpointKey = endpointProvider.currentEndpoint().canonicalServerUrl
    private val endpointChangeSubscription: AutoCloseable

    private val _accessState = MutableStateFlow(
        PlatformAccessState(
            apiAvailability = PlatformApiAvailability.UNKNOWN,
            deviceAuthorization = if (credentials.deviceToken() == null) {
                PlatformDeviceAuthorization.ENROLLMENT_REQUIRED
            } else {
                PlatformDeviceAuthorization.UNKNOWN
            }
        )
    )
    private val _products = MutableStateFlow(PlatformSnapshot<List<Product>>(emptyList()))
    private val _brokerStatus = MutableStateFlow(PlatformSnapshot<BrokerStatus?>(null))
    private val _bindings = MutableStateFlow(PlatformSnapshot<List<Binding>>(emptyList()))
    private val _tags = MutableStateFlow(PlatformSnapshot<List<Tag>>(emptyList()))
    private val _stations = MutableStateFlow(PlatformSnapshot<List<Station>>(emptyList()))
    private val _commands = MutableStateFlow<Map<UUID, LightCommand>>(emptyMap())

    override val accessState: StateFlow<PlatformAccessState> = _accessState.asStateFlow()
    override val brokerStatus: StateFlow<PlatformSnapshot<BrokerStatus?>> =
        _brokerStatus.asStateFlow()
    override val products: StateFlow<PlatformSnapshot<List<Product>>> = _products.asStateFlow()
    override val bindings: StateFlow<PlatformSnapshot<List<Binding>>> = _bindings.asStateFlow()
    override val tags: StateFlow<PlatformSnapshot<List<Tag>>> = _tags.asStateFlow()
    override val stations: StateFlow<PlatformSnapshot<List<Station>>> = _stations.asStateFlow()
    override val commands: StateFlow<Map<UUID, LightCommand>> = _commands.asStateFlow()
    override val eventConnectionState = eventStream.connectionState
    override val events: SharedFlow<PlatformEvent> = eventStream.events

    init {
        endpointChangeSubscription = endpointProvider.addEndpointChangeListener(
            ::handleEndpointChanged
        )
        scheduleCacheHydration(currentEndpointContext())
    }

    override fun startRealtime() {
        synchronized(lifecycleLock) {
            realtimeStarted = true
            if (observerJobs.isEmpty()) {
                observerJobs += scope.launch {
                    eventStream.events.collect(::applyEvent)
                }
                observerJobs += scope.launch {
                    var handledGeneration = 0L
                    eventStream.snapshotRefreshGeneration.collect { generation ->
                        if (generation > handledGeneration) {
                            handledGeneration = generation
                            refreshAllIgnoringFailure()
                        }
                    }
                }
                observerJobs += scope.launch {
                    eventStream.connectionState.collect { connectionState ->
                        if (connectionState == PlatformEventConnectionState.AuthenticationRequired) {
                            credentials.clearDeviceToken()
                            _accessState.update {
                                it.copy(
                                    deviceAuthorization =
                                        PlatformDeviceAuthorization.REVOKED_OR_INVALID
                                )
                            }
                        }
                    }
                }
            }
            if (credentials.deviceToken() != null) {
                eventStream.connect()
                if (initialRefreshJob?.isActive != true) {
                    initialRefreshJob = scope.launch { refreshAllIgnoringFailure() }
                }
            }
            startStationStatusRefreshLocked()
        }
    }

    override fun stopRealtime() {
        synchronized(lifecycleLock) {
            realtimeStarted = false
            observerJobs.forEach(Job::cancel)
            observerJobs.clear()
            bindingRefreshJob?.cancel()
            bindingRefreshJob = null
            initialRefreshJob?.cancel()
            initialRefreshJob = null
            stationEventRefreshJob?.cancel()
            stationEventRefreshJob = null
            stationStatusRefreshJob?.cancel()
            stationStatusRefreshJob = null
            eventStream.disconnect()
        }
    }

    override fun close() {
        stopRealtime()
        cacheHydrationJob?.cancel()
        endpointChangeSubscription.close()
    }

    private fun handleEndpointChanged(endpoint: PlatformEndpoint) {
        val next = synchronized(endpointLock) {
            val nextKey = endpoint.canonicalServerUrl
            if (nextKey == endpointKey) return
            endpointKey = nextKey
            endpointGeneration += 1L
            EndpointContext(nextKey, endpointGeneration)
        }

        synchronized(lifecycleLock) {
            observerJobs.forEach(Job::cancel)
            observerJobs.clear()
            bindingRefreshJob?.cancel()
            bindingRefreshJob = null
            initialRefreshJob?.cancel()
            initialRefreshJob = null
            stationEventRefreshJob?.cancel()
            stationEventRefreshJob = null
            stationStatusRefreshJob?.cancel()
            stationStatusRefreshJob = null
            eventStream.disconnect()
        }
        clearVisibleEndpointState()
        scheduleCacheHydration(next)
    }

    private fun clearVisibleEndpointState() {
        _brokerStatus.value = PlatformSnapshot(null)
        _products.value = PlatformSnapshot(emptyList())
        _bindings.value = PlatformSnapshot(emptyList())
        _tags.value = PlatformSnapshot(emptyList())
        _stations.value = PlatformSnapshot(emptyList())
        synchronized(commandLock) {
            _commands.value = emptyMap()
            pendingCommandEvents.clear()
        }
        _accessState.value = PlatformAccessState(
            apiAvailability = PlatformApiAvailability.UNKNOWN,
            deviceAuthorization = if (credentials.deviceToken() == null) {
                PlatformDeviceAuthorization.ENROLLMENT_REQUIRED
            } else {
                PlatformDeviceAuthorization.UNKNOWN
            }
        )
    }

    private fun scheduleCacheHydration(endpoint: EndpointContext) {
        cacheHydrationJob?.cancel()
        cacheHydrationJob = scope.launch {
            stateMutationMutex.withLock {
                if (!isCurrentEndpoint(endpoint)) return@withLock
                val cached = try {
                    cache.read(endpoint.key)
                } catch (_: Exception) {
                    null
                } ?: return@withLock
                if (!isCurrentEndpoint(endpoint) || cached.endpoint != endpoint.key) {
                    return@withLock
                }
                if (_products.value.synchronizedAt == null) {
                    _products.value = PlatformSnapshot(
                        value = cached.products,
                        synchronizedAt = cached.productsSynchronizedAt,
                        isStale = true
                    )
                }
                if (_bindings.value.synchronizedAt == null) {
                    _bindings.value = PlatformSnapshot(
                        value = cached.bindings,
                        synchronizedAt = cached.bindingsSynchronizedAt,
                        isStale = true
                    )
                }
                if (_tags.value.synchronizedAt == null) {
                    _tags.value = PlatformSnapshot(
                        value = cached.tags,
                        synchronizedAt = cached.tagsSynchronizedAt,
                        isStale = true
                    )
                }
            }
        }
    }

    override fun setStationStatusRefreshActive(active: Boolean) {
        synchronized(lifecycleLock) {
            stationStatusRefreshRequested = active
            if (active && realtimeStarted) {
                startStationStatusRefreshLocked()
            } else {
                stationStatusRefreshJob?.cancel()
                stationStatusRefreshJob = null
                stationEventRefreshJob?.cancel()
                stationEventRefreshJob = null
            }
        }
    }

    override fun newWriteIdempotencyKey(): IdempotencyKey = idempotencyKeys.create()

    override suspend fun refreshAll() = stateMutationMutex.withLock {
        val endpoint = currentEndpointContext()
        requireTokenForRead()
        setAllRefreshing(true)
        _accessState.update { it.copy(apiAvailability = PlatformApiAvailability.CHECKING) }
        try {
            val readiness = api.getReadiness()
            val coreApiNotReady = readiness.checks.any { check ->
                check.name != ReadinessCheckName.MQTT_BRIDGE &&
                    check.status == CheckStatus.NOT_READY
            }
            if (coreApiNotReady) {
                throw PlatformWriteClosedException(PlatformWriteClosedReason.API_UNAVAILABLE)
            }
            val nextBrokerStatus = api.getBrokerStatus()
            val nextProducts = fetchAll(endpoint) { api.listProducts(it) }
            val nextBindings = fetchAll(endpoint) { api.listBindings(it) }
                .filter(Binding::isActive)
            val nextTags = fetchAll(endpoint) { api.listTags(it) }
            val nextStations = fetchAll(endpoint) { api.listStations(it) }
            val synchronizedAt = now()
            ensureCurrentEndpoint(endpoint)
            cache.replaceAll(
                endpoint = endpoint.key,
                products = nextProducts,
                bindings = nextBindings,
                tags = nextTags,
                synchronizedAt = synchronizedAt
            )
            ensureCurrentEndpoint(endpoint)
            _brokerStatus.value = PlatformSnapshot(
                nextBrokerStatus,
                synchronizedAt,
                isStale = false
            )
            _products.value = PlatformSnapshot(nextProducts, synchronizedAt, isStale = false)
            _bindings.value = PlatformSnapshot(nextBindings, synchronizedAt, isStale = false)
            _tags.value = PlatformSnapshot(nextTags, synchronizedAt, isStale = false)
            _stations.value = PlatformSnapshot(nextStations, synchronizedAt, isStale = false)
            recordAuthenticatedSuccess(synchronizedAt)
        } catch (error: CancellationException) {
            if (isCurrentEndpoint(endpoint)) setAllRefreshing(false)
            throw error
        } catch (error: Exception) {
            if (isCurrentEndpoint(endpoint)) {
                markAllStale()
                recordFailure(error)
            }
            throw error
        }
    }

    override suspend fun refreshProducts() = stateMutationMutex.withLock {
        val endpoint = currentEndpointContext()
        refreshOne(
            endpoint = endpoint,
            state = _products,
            loader = { fetchAll(endpoint) { api.listProducts(it) } },
            persist = { loaded, at -> cache.replaceProducts(endpoint.key, loaded, at) }
        )
    }

    override suspend fun refreshBrokerStatus() = stateMutationMutex.withLock {
        val endpoint = currentEndpointContext()
        requireTokenForRead()
        _brokerStatus.update { it.copy(isRefreshing = true) }
        try {
            val loaded = api.getBrokerStatus()
            val synchronizedAt = now()
            ensureCurrentEndpoint(endpoint)
            _brokerStatus.value = PlatformSnapshot(loaded, synchronizedAt, isStale = false)
            recordAuthenticatedSuccess(synchronizedAt)
        } catch (error: CancellationException) {
            if (isCurrentEndpoint(endpoint)) {
                _brokerStatus.update { it.copy(isRefreshing = false) }
            }
            throw error
        } catch (error: Exception) {
            if (isCurrentEndpoint(endpoint)) {
                _brokerStatus.update { it.copy(isRefreshing = false, isStale = true) }
                recordFailure(error)
            }
            throw error
        }
    }

    override suspend fun refreshBindings() = stateMutationMutex.withLock {
        val endpoint = currentEndpointContext()
        refreshOne(
            endpoint = endpoint,
            state = _bindings,
            loader = {
                fetchAll(endpoint) { api.listBindings(it) }.filter(Binding::isActive)
            },
            persist = { loaded, at -> cache.replaceBindings(endpoint.key, loaded, at) }
        )
    }

    override suspend fun refreshTags() = stateMutationMutex.withLock {
        val endpoint = currentEndpointContext()
        refreshOne(
            endpoint = endpoint,
            state = _tags,
            loader = { fetchAll(endpoint) { api.listTags(it) } },
            persist = { loaded, at -> cache.replaceTags(endpoint.key, loaded, at) }
        )
    }

    override suspend fun refreshStations() = stateMutationMutex.withLock {
        val endpoint = currentEndpointContext()
        refreshOne(
            endpoint = endpoint,
            state = _stations,
            loader = { fetchAll(endpoint) { api.listStations(it) } }
        )
    }

    override suspend fun bind(
        request: BindingCreateRequest,
        idempotencyKey: IdempotencyKey?
    ): Binding = performWrite { endpoint ->
        val created = api.createBinding(request, idempotencyKey ?: idempotencyKeys.create())
        ensureCurrentEndpoint(endpoint)
        val synchronizedAt = now()
        cache.upsertBinding(endpoint.key, created, synchronizedAt)
        ensureCurrentEndpoint(endpoint)
        _bindings.update { snapshot ->
            snapshot.copy(
                value = snapshot.value
                    .filterNot { it.id == created.id || it.tagId == created.tagId }
                    .plus(created),
                synchronizedAt = synchronizedAt,
                isStale = false
            )
        }
        _products.update { it.copy(isStale = true) }
        created
    }

    override suspend fun rebind(
        bindingId: UUID,
        request: RebindRequest,
        idempotencyKey: IdempotencyKey?
    ): RebindResult =
        performWrite { endpoint ->
            val result = api.rebind(
                bindingId,
                request,
                idempotencyKey ?: idempotencyKeys.create()
            )
            ensureCurrentEndpoint(endpoint)
            val synchronizedAt = now()
            cache.replaceBinding(
                endpoint.key,
                result.removedBinding.id,
                result.createdBinding,
                synchronizedAt
            )
            ensureCurrentEndpoint(endpoint)
            _bindings.update { snapshot ->
                snapshot.copy(
                    value = snapshot.value
                        .filterNot {
                            it.id == result.removedBinding.id ||
                                it.id == result.createdBinding.id ||
                                it.tagId == result.createdBinding.tagId
                        }
                        .plus(result.createdBinding),
                    synchronizedAt = synchronizedAt,
                    isStale = false
                )
            }
            _products.update { it.copy(isStale = true) }
            result
        }

    override suspend fun unbind(bindingId: UUID, idempotencyKey: IdempotencyKey?): Binding =
        performWrite { endpoint ->
        val removed = api.unbind(bindingId, idempotencyKey ?: idempotencyKeys.create())
        ensureCurrentEndpoint(endpoint)
        val synchronizedAt = now()
        cache.removeBinding(endpoint.key, removed.id, synchronizedAt)
        ensureCurrentEndpoint(endpoint)
            _bindings.update { snapshot ->
                snapshot.copy(
                    value = snapshot.value.filterNot { it.id == removed.id },
                    synchronizedAt = synchronizedAt,
                    isStale = false
                )
            }
            _products.update { it.copy(isStale = true) }
            removed
    }

    override suspend fun previewAndroidBindingMigration(
        request: AndroidBindingMigrationPreviewRequest
    ): AndroidBindingMigrationPreview = performAuthenticatedRead { endpoint ->
        val preview = api.previewAndroidBindingMigration(request)
        ensureCurrentEndpoint(endpoint)
        validateMigrationPreview(request, preview)
        preview
    }

    override suspend fun commitAndroidBindingMigration(
        request: AndroidBindingMigrationCommitRequest,
        idempotencyKey: IdempotencyKey
    ): AndroidBindingMigrationCommitResult = performWrite { endpoint ->
        val committed = api.commitAndroidBindingMigration(request, idempotencyKey)
        ensureCurrentEndpoint(endpoint)
        validateMigrationCommit(request, committed)
        cache.mergeBindings(endpoint.key, committed.bindings, committed.committedAt)
        ensureCurrentEndpoint(endpoint)
        if (committed.bindings.isNotEmpty()) {
            val committedIds = committed.bindings.map { it.id }.toSet()
            val committedTags = committed.bindings.map { it.tagId }.toSet()
            _bindings.update { snapshot ->
                snapshot.copy(
                    value = snapshot.value
                        .filterNot { it.id in committedIds || it.tagId in committedTags }
                        .plus(committed.bindings),
                    synchronizedAt = committed.committedAt
                )
            }
        }
        if (committed.summary.migratedRecords > 0) {
            _products.update { it.copy(isStale = true) }
            _tags.update { it.copy(isStale = true) }
        }
        committed
    }

    override suspend fun registerTag(
        request: TagRegisterRequest,
        idempotencyKey: IdempotencyKey?
    ): Tag = performWrite { endpoint ->
        val registered = api.registerTag(request, idempotencyKey ?: idempotencyKeys.create())
        ensureCurrentEndpoint(endpoint)
        val synchronizedAt = now()
        cache.upsertTag(endpoint.key, registered, synchronizedAt)
        ensureCurrentEndpoint(endpoint)
        _tags.update { snapshot ->
            snapshot.copy(
                value = snapshot.value.filterNot { it.tagId == registered.tagId }.plus(registered),
                synchronizedAt = synchronizedAt,
                isStale = false
            )
        }
        registered
    }

    override suspend fun sendLightCommand(
        request: LightCommandRequest,
        idempotencyKey: IdempotencyKey?
    ): LightCommand =
        performWrite { endpoint ->
            val command = api.createLightCommand(
                request,
                idempotencyKey ?: idempotencyKeys.create()
            )
            ensureCurrentEndpoint(endpoint)
            storeCommand(command)
            command
        }

    override suspend fun allOff(
        stationId: String,
        idempotencyKey: IdempotencyKey?
    ): LightCommand = performWrite { endpoint ->
        val command = api.allOff(stationId, idempotencyKey ?: idempotencyKeys.create())
        ensureCurrentEndpoint(endpoint)
        storeCommand(command)
        command
    }

    override suspend fun command(commandId: UUID): LightCommand = performAuthenticatedRead { endpoint ->
        val command = api.getLightCommand(commandId)
        ensureCurrentEndpoint(endpoint)
        storeCommand(command)
        command
    }

    private suspend fun <T> refreshOne(
        endpoint: EndpointContext,
        state: MutableStateFlow<PlatformSnapshot<List<T>>>,
        loader: suspend () -> List<T>,
        persist: suspend (List<T>, Instant) -> Unit = { _, _ -> }
    ) {
        requireTokenForRead()
        state.update { it.copy(isRefreshing = true) }
        try {
            val loaded = loader()
            val synchronizedAt = now()
            ensureCurrentEndpoint(endpoint)
            persist(loaded, synchronizedAt)
            ensureCurrentEndpoint(endpoint)
            state.value = PlatformSnapshot(loaded, synchronizedAt, isStale = false)
            recordAuthenticatedSuccess(synchronizedAt)
        } catch (error: CancellationException) {
            if (isCurrentEndpoint(endpoint)) state.update { it.copy(isRefreshing = false) }
            throw error
        } catch (error: Exception) {
            if (isCurrentEndpoint(endpoint)) {
                state.update { it.copy(isRefreshing = false, isStale = true) }
                recordFailure(error)
            }
            throw error
        }
    }

    private suspend fun <T> performWrite(block: suspend (EndpointContext) -> T): T =
        stateMutationMutex.withLock {
            val endpoint = currentEndpointContext()
            ensureWritable()
            try {
                block(endpoint).also {
                    ensureCurrentEndpoint(endpoint)
                    recordAuthenticatedSuccess(now())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrentEndpoint(endpoint)) recordFailure(error)
                throw error
            }
        }

    private suspend fun <T> performAuthenticatedRead(
        block: suspend (EndpointContext) -> T
    ): T = stateMutationMutex.withLock {
        val endpoint = currentEndpointContext()
        requireTokenForRead()
        try {
            block(endpoint).also {
                ensureCurrentEndpoint(endpoint)
                recordAuthenticatedSuccess(now())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isCurrentEndpoint(endpoint)) recordFailure(error)
            throw error
        }
    }

    private fun ensureWritable() {
        val access = _accessState.value
        if (access.canWrite) return
        val reason = when {
            access.apiAvailability == PlatformApiAvailability.CONTRACT_INCOMPATIBLE ->
                PlatformWriteClosedReason.CONTRACT_INCOMPATIBLE
            access.apiAvailability == PlatformApiAvailability.UNAVAILABLE ->
                PlatformWriteClosedReason.API_UNAVAILABLE
            access.deviceAuthorization != PlatformDeviceAuthorization.APPROVED ->
                PlatformWriteClosedReason.DEVICE_NOT_APPROVED
            else -> PlatformWriteClosedReason.API_NOT_VERIFIED
        }
        throw PlatformWriteClosedException(reason)
    }

    private fun requireTokenForRead() {
        if (credentials.deviceToken() != null) return
        _accessState.update {
            it.copy(deviceAuthorization = PlatformDeviceAuthorization.ENROLLMENT_REQUIRED)
        }
        throw PlatformAuthenticationException()
    }

    private fun currentEndpointContext(): EndpointContext = synchronized(endpointLock) {
        EndpointContext(endpointKey, endpointGeneration)
    }

    private fun isCurrentEndpoint(expected: EndpointContext): Boolean =
        synchronized(endpointLock) {
            endpointKey == expected.key && endpointGeneration == expected.generation
        }

    private fun ensureCurrentEndpoint(expected: EndpointContext) {
        if (!isCurrentEndpoint(expected)) throw PlatformEndpointChangedException()
    }

    private fun validateMigrationPreview(
        request: AndroidBindingMigrationPreviewRequest,
        preview: AndroidBindingMigrationPreview
    ) {
        val responseKeys = preview.records.map { it.clientRecordKey }
        if (request.records.map { it.clientRecordKey } != responseKeys ||
            request.records.zip(preview.records).any { (sent, received) ->
                PlatformValueRules.normalizeProductCode(sent.productCode) != received.productCode ||
                    normalizeMigrationTagId(sent.tagId) != received.tagId ||
                    sent.stationId.uppercase() != received.stationId ||
                    sent.productName?.takeIf(String::isNotEmpty) != received.productName
            }
        ) {
            throw PlatformProtocolException()
        }
    }

    private fun validateMigrationCommit(
        request: AndroidBindingMigrationCommitRequest,
        committed: AndroidBindingMigrationCommitResult
    ) {
        if (request.records.map { it.clientRecordKey } !=
            committed.records.map { it.clientRecordKey }
        ) {
            throw PlatformProtocolException()
        }
    }

    private fun normalizeMigrationTagId(value: String): String {
        val normalized = value.uppercase()
        return if (normalized.length == 9) "AD1$normalized" else normalized
    }

    private suspend fun <T> fetchAll(
        endpoint: EndpointContext,
        loader: suspend (PageRequest) -> Page<T>
    ): List<T> {
        val items = mutableListOf<T>()
        var pageNumber = 1
        var expectedTotalItems: Long? = null
        var expectedTotalPages: Int? = null
        while (true) {
            ensureCurrentEndpoint(endpoint)
            val page = loader(PageRequest(page = pageNumber, pageSize = SNAPSHOT_PAGE_SIZE))
            ensureCurrentEndpoint(endpoint)
            val pagination = page.pagination
            val calculatedTotalPages = pagination.totalItems / SNAPSHOT_PAGE_SIZE +
                if (pagination.totalItems % SNAPSHOT_PAGE_SIZE == 0L) 0L else 1L
            if (page.pagination.page != pageNumber ||
                page.pagination.pageSize != SNAPSHOT_PAGE_SIZE ||
                page.pagination.totalItems < 0L ||
                page.pagination.totalPages !in 0..MAX_SNAPSHOT_PAGES ||
                calculatedTotalPages != page.pagination.totalPages.toLong() ||
                expectedTotalItems?.let { it != page.pagination.totalItems } == true ||
                expectedTotalPages?.let { it != page.pagination.totalPages } == true ||
                page.items.size > SNAPSHOT_PAGE_SIZE ||
                (page.pagination.totalPages == 0 && page.items.isNotEmpty()) ||
                (pageNumber < page.pagination.totalPages &&
                    page.items.size != SNAPSHOT_PAGE_SIZE)
            ) {
                throw PlatformProtocolException()
            }
            expectedTotalItems = page.pagination.totalItems
            expectedTotalPages = page.pagination.totalPages
            items += page.items
            if (pageNumber >= page.pagination.totalPages) {
                if (items.size.toLong() != page.pagination.totalItems) {
                    throw PlatformProtocolException()
                }
                break
            }
            pageNumber += 1
        }
        return items
    }

    private fun applyEvent(event: PlatformEvent) {
        when (event) {
            is StationStatusChangedEvent -> applyStationStatus(event)
            is StationHeartbeatEvent -> applyStationHeartbeat(event)
            is TagStatusChangedEvent -> applyTagStatus(event)
            is CommandStatusChangedEvent -> applyCommandStatus(event)
            is BrokerStatusChangedEvent -> applyBrokerStatus(event)
            is BindingCreatedEvent,
            is BindingRemovedEvent -> scheduleBindingSnapshotRefresh()
            is DeviceStatusChangedEvent -> applyDeviceStatus(event)
            else -> Unit
        }
    }

    private fun applyBrokerStatus(event: BrokerStatusChangedEvent) {
        _brokerStatus.update { snapshot ->
            val current = snapshot.value
            if (current == null) {
                snapshot.copy(isStale = true)
            } else {
                snapshot.copy(
                    value = current.copy(
                        serviceState = event.payload.currentStatus,
                        endpoint = event.payload.endpoint,
                        tcpReachable = event.payload.tcpReachable,
                        mqttConnected = event.payload.mqttConnected,
                        subscriptionsReady = event.payload.subscriptionsReady,
                        checkedAt = event.occurredAt
                    ),
                    isStale = false
                )
            }
        }
    }

    private fun applyStationStatus(event: StationStatusChangedEvent) {
        var matched = false
        _stations.update { snapshot ->
            snapshot.copy(
                value = snapshot.value.map { station ->
                    if (station.stationId == event.payload.stationId) {
                        matched = true
                        station.copy(
                            status = event.payload.currentStatus,
                            lastHeartbeatAt = event.payload.lastHeartbeatAt,
                            brokerConnected = event.payload.brokerConnected,
                            updatedAt = event.occurredAt
                        )
                    } else {
                        station
                    }
                },
                isStale = snapshot.isStale || !matched
            )
        }
        if (!matched) scheduleStationSnapshotRefresh()
    }

    private fun applyStationHeartbeat(event: StationHeartbeatEvent) {
        var matched = false
        _stations.update { snapshot ->
            snapshot.copy(
                value = snapshot.value.map { station ->
                    if (station.stationId == event.payload.stationId) {
                        matched = true
                        station.copy(
                            alias = event.payload.alias,
                            status = event.payload.status,
                            mac = event.payload.mac,
                            firmwareVersion = event.payload.firmwareVersion,
                            serverAddress = event.payload.serverAddress,
                            heartbeatSeconds = event.payload.heartbeatSeconds,
                            lastHeartbeatAt = event.occurredAt,
                            totalCount = event.payload.totalCount,
                            sendCount = event.payload.sendCount,
                            updatedAt = event.occurredAt
                        )
                    } else {
                        station
                    }
                },
                isStale = snapshot.isStale || !matched
            )
        }
        if (!matched) scheduleStationSnapshotRefresh()
    }

    private fun applyTagStatus(event: TagStatusChangedEvent) {
        var matched = false
        _tags.update { snapshot ->
            snapshot.copy(
                value = snapshot.value.map { tag ->
                    if (tag.tagId == event.payload.tagId) {
                        matched = true
                        tag.copy(
                            stationId = event.payload.stationId,
                            lastSeenAt = event.payload.lastSeenAt,
                            online = event.payload.online,
                            batteryRaw = event.payload.batteryRaw,
                            batteryVoltage = event.payload.batteryVoltage,
                            batteryLevel = event.payload.batteryLevel,
                            lowBattery = event.payload.lowBattery,
                            lastResultType = event.payload.lastResultType,
                            isAbnormal = event.payload.isAbnormal,
                            abnormalReason = event.payload.abnormalReason
                        )
                    } else {
                        tag
                    }
                },
                isStale = snapshot.isStale || !matched
            )
        }
    }

    private fun applyCommandStatus(event: CommandStatusChangedEvent) {
        synchronized(commandLock) {
            if (_commands.value[event.payload.commandId] == null) {
                if (event.payload.commandId !in pendingCommandEvents &&
                    pendingCommandEvents.size >= MAX_PENDING_COMMAND_IDS
                ) {
                    pendingCommandEvents.remove(pendingCommandEvents.keys.first())
                }
                val pending = pendingCommandEvents.getOrPut(event.payload.commandId, ::mutableListOf)
                if (pending.size < MAX_PENDING_COMMAND_EVENTS) pending += event
                return
            }
            applyCommandStatusLocked(event)
        }
    }

    private fun applyCommandStatusLocked(event: CommandStatusChangedEvent) {
        _commands.update { current ->
            val command = current[event.payload.commandId] ?: return@update current
            val updatedItems = event.payload.item?.let { itemUpdate ->
                command.items.map { item ->
                    if (item.id == itemUpdate.itemId) {
                        item.copy(
                            status = itemUpdate.currentStatus,
                            confirmedAt = if (itemUpdate.currentStatus == CommandItemStatus.CONFIRMED) {
                                event.occurredAt
                            } else {
                                item.confirmedAt
                            },
                            lastResultType = itemUpdate.resultType,
                            correlation = itemUpdate.correlation
                        )
                    } else {
                        item
                    }
                }
            } ?: command.items
            current + (command.id to command.copy(
                status = event.payload.currentStatus,
                targetCount = event.payload.targetCount,
                confirmedCount = event.payload.confirmedCount,
                unconfirmedCount = event.payload.unconfirmedCount,
                failedCount = event.payload.failedCount,
                completedAt = if (event.payload.currentStatus.isTerminal()) {
                    event.occurredAt
                } else {
                    command.completedAt
                },
                items = updatedItems
            ))
        }
    }

    private fun applyDeviceStatus(event: DeviceStatusChangedEvent) {
        if (credentials.deviceId() != event.payload.deviceId) return
        when (event.payload.currentStatus) {
            DeviceStatus.APPROVED -> _accessState.update {
                it.copy(deviceAuthorization = PlatformDeviceAuthorization.APPROVED)
            }
            DeviceStatus.PENDING -> _accessState.update {
                it.copy(deviceAuthorization = PlatformDeviceAuthorization.UNKNOWN)
            }
            DeviceStatus.REVOKED -> {
                credentials.clearDeviceToken()
                _accessState.update {
                    it.copy(deviceAuthorization = PlatformDeviceAuthorization.REVOKED_OR_INVALID)
                }
                eventStream.disconnect()
            }
        }
    }

    private fun scheduleBindingSnapshotRefresh() {
        _bindings.update { it.copy(isStale = true) }
        _products.update { it.copy(isStale = true) }
        synchronized(lifecycleLock) {
            bindingRefreshJob?.cancel()
            bindingRefreshJob = scope.launch {
                delay(BINDING_REFRESH_DEBOUNCE_MILLIS)
                try {
                    stateMutationMutex.withLock {
                        val endpoint = currentEndpointContext()
                        refreshOne(
                            endpoint = endpoint,
                            state = _bindings,
                            loader = {
                                fetchAll(endpoint) { api.listBindings(it) }
                                    .filter(Binding::isActive)
                            },
                            persist = { loaded, at ->
                                cache.replaceBindings(endpoint.key, loaded, at)
                            }
                        )
                        refreshOne(
                            endpoint = endpoint,
                            state = _products,
                            loader = { fetchAll(endpoint) { api.listProducts(it) } },
                            persist = { loaded, at ->
                                cache.replaceProducts(endpoint.key, loaded, at)
                            }
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Unit
                }
            }
        }
    }

    private fun scheduleStationSnapshotRefresh() {
        synchronized(lifecycleLock) {
            if (!realtimeStarted || !stationStatusRefreshRequested) return
            stationEventRefreshJob?.cancel()
            stationEventRefreshJob = scope.launch {
                delay(STATION_EVENT_REFRESH_DEBOUNCE_MILLIS)
                try {
                    refreshStations()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Unit
                }
            }
        }
    }

    private fun startStationStatusRefreshLocked() {
        if (!stationStatusRefreshRequested || stationStatusRefreshJob?.isActive == true) return
        stationStatusRefreshJob = scope.launch {
            var consecutiveFailures = 0
            while (true) {
                val baseInterval = StationStatusRefreshPolicy.intervalFor(_stations.value.value)
                val nextDelay = if (credentials.deviceToken() == null) {
                    consecutiveFailures = 0
                    StationStatusRefreshPolicy.MAX_FAILURE_INTERVAL_MILLIS
                } else {
                    try {
                        refreshStations()
                        consecutiveFailures = 0
                        StationStatusRefreshPolicy.intervalFor(_stations.value.value)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        consecutiveFailures += 1
                        StationStatusRefreshPolicy.retryDelay(
                            baseIntervalMillis = baseInterval,
                            consecutiveFailures = consecutiveFailures
                        )
                    }
                }
                stationRefreshSleeper(nextDelay)
            }
        }
    }

    private fun recordAuthenticatedSuccess(at: Instant) {
        _accessState.value = PlatformAccessState(
            apiAvailability = PlatformApiAvailability.AVAILABLE,
            deviceAuthorization = PlatformDeviceAuthorization.APPROVED,
            lastVerifiedAt = at
        )
    }

    private fun recordFailure(error: Exception) {
        when (error) {
            is PlatformAuthenticationException -> _accessState.update {
                it.copy(deviceAuthorization = PlatformDeviceAuthorization.ENROLLMENT_REQUIRED)
            }
            is PlatformTransportException,
            is PlatformWriteClosedException -> _accessState.update {
                it.copy(apiAvailability = PlatformApiAvailability.UNAVAILABLE)
            }
            is PlatformProtocolException -> _accessState.update {
                it.copy(apiAvailability = PlatformApiAvailability.CONTRACT_INCOMPATIBLE)
            }
            is PlatformApiException -> when {
                error.isAuthenticationFailure -> {
                    credentials.clearDeviceToken()
                    _accessState.update {
                        it.copy(deviceAuthorization = PlatformDeviceAuthorization.REVOKED_OR_INVALID)
                    }
                    eventStream.disconnect()
                }
                error.isUnavailable -> _accessState.update {
                    it.copy(apiAvailability = PlatformApiAvailability.UNAVAILABLE)
                }
            }
        }
    }

    private fun setAllRefreshing(refreshing: Boolean) {
        _brokerStatus.update { it.copy(isRefreshing = refreshing) }
        _products.update { it.copy(isRefreshing = refreshing) }
        _bindings.update { it.copy(isRefreshing = refreshing) }
        _tags.update { it.copy(isRefreshing = refreshing) }
        _stations.update { it.copy(isRefreshing = refreshing) }
    }

    private fun markAllStale() {
        _brokerStatus.update { it.copy(isRefreshing = false, isStale = true) }
        _products.update { it.copy(isRefreshing = false, isStale = true) }
        _bindings.update { it.copy(isRefreshing = false, isStale = true) }
        _tags.update { it.copy(isRefreshing = false, isStale = true) }
        _stations.update { it.copy(isRefreshing = false, isStale = true) }
    }

    private fun storeCommand(command: LightCommand) {
        synchronized(commandLock) {
            _commands.update { it + (command.id to command) }
            pendingCommandEvents.remove(command.id)?.forEach(::applyCommandStatusLocked)
        }
    }

    private suspend fun refreshAllIgnoringFailure() {
        try {
            refreshAll()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Unit
        }
    }

    private fun com.example.deepchatdemo.platform.model.CommandStatus.isTerminal(): Boolean =
        this == com.example.deepchatdemo.platform.model.CommandStatus.CONFIRMED ||
            this == com.example.deepchatdemo.platform.model.CommandStatus.PARTIALLY_CONFIRMED ||
            this == com.example.deepchatdemo.platform.model.CommandStatus.UNCONFIRMED ||
            this == com.example.deepchatdemo.platform.model.CommandStatus.FAILED ||
            this == com.example.deepchatdemo.platform.model.CommandStatus.SUPERSEDED

    private companion object {
        const val SNAPSHOT_PAGE_SIZE = 100
        const val MAX_SNAPSHOT_PAGES = 10_000
        const val BINDING_REFRESH_DEBOUNCE_MILLIS = 150L
        const val STATION_EVENT_REFRESH_DEBOUNCE_MILLIS = 150L
        const val MAX_PENDING_COMMAND_EVENTS = 2_000
        const val MAX_PENDING_COMMAND_IDS = 128
    }

    private data class EndpointContext(
        val key: String,
        val generation: Long
    )
}
