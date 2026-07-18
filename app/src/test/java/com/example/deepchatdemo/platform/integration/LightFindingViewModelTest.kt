package com.example.deepchatdemo.platform.integration

import com.example.deepchatdemo.light.data.InMemoryLightBindingRepository
import com.example.deepchatdemo.light.domain.LightBinding
import com.example.deepchatdemo.platform.config.PlatformConfigStore
import com.example.deepchatdemo.platform.config.PlatformEndpoint
import com.example.deepchatdemo.platform.config.PlatformUrlValidation
import com.example.deepchatdemo.platform.config.PlatformUrlValidator
import com.example.deepchatdemo.platform.model.ActorType
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationClassification
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRecord
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitResult
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitSummary
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationOutcome
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreview
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRecord
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewSummary
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationRecord
import com.example.deepchatdemo.platform.model.ApiError
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingCreateRequest
import com.example.deepchatdemo.platform.model.BindingSource
import com.example.deepchatdemo.platform.model.BrokerServiceState
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.CommandStatus
import com.example.deepchatdemo.platform.model.LightAction
import com.example.deepchatdemo.platform.model.LightCommand
import com.example.deepchatdemo.platform.model.LightCommandRequest
import com.example.deepchatdemo.platform.model.PlatformEvent
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.RebindRequest
import com.example.deepchatdemo.platform.model.RebindResult
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.Tag
import com.example.deepchatdemo.platform.model.TagRegisterRequest
import com.example.deepchatdemo.platform.network.IdempotencyKey
import com.example.deepchatdemo.platform.network.IdempotencyKeyFactory
import com.example.deepchatdemo.platform.network.PlatformApiException
import com.example.deepchatdemo.platform.network.PlatformEventConnectionState
import com.example.deepchatdemo.platform.network.PlatformTransportException
import com.example.deepchatdemo.platform.repository.PlatformAccessState
import com.example.deepchatdemo.platform.repository.PlatformApiAvailability
import com.example.deepchatdemo.platform.repository.PlatformDeviceAuthorization
import com.example.deepchatdemo.platform.repository.PlatformRepository
import com.example.deepchatdemo.platform.repository.PlatformSnapshot
import com.example.deepchatdemo.platform.security.DeviceEnrollmentController
import com.example.deepchatdemo.platform.security.EnrollmentClientState
import com.example.deepchatdemo.platform.model.DeviceEnrollmentCreated
import com.example.deepchatdemo.platform.model.DeviceEnrollmentState
import com.example.deepchatdemo.ui.light.LightFindingViewModel
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightFindingViewModelTest {
    @Test
    fun productLightCommandLetsServerTargetEveryBoundTag() {
        val repository = FakePlatformRepository(
            remoteBindings = listOf(
                remoteBinding("binding-1", "PRODUCT-A", "AD1000165FC2"),
                remoteBinding("binding-2", "PRODUCT-A", "AD1000161C7A")
            )
        )
        val scope = testScope()
        val viewModel = viewModel(repository, scope)

        viewModel.lightByItemCode("product-a")

        val request = repository.lightCommands.single()
        assertEquals(LightAction.LIGHT_ON, request.action)
        assertEquals("PRODUCT-A", request.productCode)
        assertNull(request.tagIds)
        assertEquals(2, viewModel.uiState.bindings.count { it.itemCode == "PRODUCT-A" })
        scope.cancel()
    }

    @Test
    fun unavailablePlatformClosesUiWritesBeforeRepositoryCall() {
        val repository = FakePlatformRepository(
            remoteBindings = listOf(
                remoteBinding("binding-1", "PRODUCT-A", "AD1000165FC2")
            ),
            unavailable = true
        )
        val scope = testScope()
        val viewModel = viewModel(repository, scope)

        viewModel.lightByItemCode("PRODUCT-A")

        assertTrue(repository.lightCommands.isEmpty())
        assertFalse(viewModel.uiState.canWrite)
        assertTrue(viewModel.uiState.inputMessage.orEmpty().contains("后台不可用"))
        scope.cancel()
    }

    @Test
    fun staleCachedBindingRemainsVisibleWithoutEnablingWriteOrScanGate() {
        val cached = remoteBinding("cached", "PRODUCT-A", "AD1000165FC2")
        val repository = FakePlatformRepository(
            remoteBindings = listOf(cached),
            unavailable = true,
            snapshotsStale = true
        )
        val scope = testScope()
        val viewModel = viewModel(repository, scope)

        assertEquals("PRODUCT-A", viewModel.uiState.bindings.single().itemCode)
        assertEquals(NOW, viewModel.uiState.cacheSynchronizedAt)
        assertTrue(viewModel.uiState.cacheStale)
        assertFalse(viewModel.uiState.canWrite)

        viewModel.lightByItemCode("PRODUCT-A")
        assertTrue(repository.lightCommands.isEmpty())
        scope.cancel()
    }

    @Test
    fun screenActivityControlsStationStatusRefresh() {
        val repository = FakePlatformRepository()
        val scope = testScope()
        val viewModel = viewModel(repository, scope)

        viewModel.onScreenActiveChanged(true)
        viewModel.onScreenActiveChanged(false)

        assertEquals(listOf(true, false), repository.stationRefreshStates)
        scope.cancel()
    }

    @Test
    fun initialSnapshotCompletionReplacesTheSyncingMessage() {
        val repository = FakePlatformRepository(initialSnapshotsSynchronized = false)
        val scope = testScope()
        val viewModel = viewModel(repository, scope)

        assertEquals("正在同步 HighTac Platform 快照。", viewModel.uiState.inputMessage)

        repository.publishFreshSnapshots()

        assertEquals("HighTac Platform 快照已同步。", viewModel.uiState.inputMessage)
        assertEquals(NOW, viewModel.uiState.cacheSynchronizedAt)
        assertFalse(viewModel.uiState.cacheStale)
        scope.cancel()
    }

    @Test
    fun migrationUsesOnePreviewAndOneAtomicCommitWithExplicitDuplicateSelection() {
        val duplicateA = legacy("duplicate-a", "PRODUCT-A", "AD1000165FC2")
        val duplicateB = legacy("duplicate-b", "PRODUCT-B", "AD1000165FC2")
        val unique = legacy("unique", "PRODUCT-C", "AD100000048F")
        val repository = FakePlatformRepository()
        val reviews = FakeMigrationReviewStore()
        val scope = testScope()
        val viewModel = viewModel(
            repository = repository,
            scope = scope,
            legacyBindings = listOf(duplicateA, duplicateB, unique),
            reviews = reviews
        )
        val preview = requireNotNull(viewModel.uiState.legacyMigration)
        assertEquals(2, preview.plan.conflicts.size)
        assertEquals(listOf("unique"), preview.plan.migratable.map { it.id })
        assertEquals(1, repository.previewRequests.size)
        assertEquals(3, repository.previewRequests.single().records.size)

        viewModel.selectLegacyMigrationCandidate(duplicateB)
        viewModel.confirmLegacyBindingMigration()

        assertEquals(1, repository.commitRequests.size)
        assertTrue(repository.recordWrites.isEmpty())
        val selectedKey = repository.commitRequests.single().first.selectedDuplicateKeys.single()
        assertEquals(
            duplicateB.id,
            preview.preparation.legacyFor(selectedKey)?.id
        )
        val completed = requireNotNull(viewModel.uiState.legacyMigration)
        assertTrue(completed.isComplete)
        assertEquals(1, completed.plan.conflicts.size)
        assertEquals(setOf("unique", "duplicate-b"), completed.migrated.map { it.id }.toSet())
        assertFalse(reviews.reviewed)
        scope.cancel()
    }

    @Test
    fun staleCommitClearsSelectionsRepreviewsAndNeverAutoCommits() {
        val legacy = legacy("legacy", "PRODUCT-A", "AD1000165FC2")
        val repository = FakePlatformRepository(
            staleCommitOnce = true,
            previewProvider = { request, call ->
                request.records.associate { record ->
                    record.clientRecordKey to if (call == 1) {
                        AndroidBindingMigrationClassification.MIGRATABLE
                    } else {
                        AndroidBindingMigrationClassification.TAG_BOUND_TO_DIFFERENT_PRODUCT
                    }
                }
            }
        )
        val reviews = FakeMigrationReviewStore()
        val scope = testScope()
        val viewModel = viewModel(
            repository = repository,
            scope = scope,
            legacyBindings = listOf(legacy),
            reviews = reviews
        )

        viewModel.confirmLegacyBindingMigration()

        assertEquals(2, repository.previewRequests.size)
        assertEquals(1, repository.commitRequests.size)
        val refreshed = requireNotNull(viewModel.uiState.legacyMigration)
        assertFalse(refreshed.isApplying)
        assertFalse(refreshed.isComplete)
        assertTrue(refreshed.selectedDuplicateCandidates.isEmpty())
        assertEquals(
            LegacyBindingConflictReason.TAG_BOUND_TO_DIFFERENT_PRODUCT,
            refreshed.plan.conflicts.single().reason
        )
        assertFalse(reviews.reviewed)
        scope.cancel()
    }

    @Test
    fun transportRetryReusesCommitBodyAndIdempotencyKey() {
        val legacy = legacy("legacy", "PRODUCT-A", "AD1000165FC2")
        val repository = FakePlatformRepository(transportFailureOnce = true)
        val reviews = FakeMigrationReviewStore()
        val scope = testScope()
        val viewModel = viewModel(
            repository = repository,
            scope = scope,
            legacyBindings = listOf(legacy),
            reviews = reviews
        )

        viewModel.confirmLegacyBindingMigration()
        assertEquals(1, repository.commitRequests.size)
        assertFalse(requireNotNull(viewModel.uiState.legacyMigration).isApplying)

        viewModel.confirmLegacyBindingMigration()

        assertEquals(2, repository.commitRequests.size)
        assertEquals(repository.commitRequests[0].first, repository.commitRequests[1].first)
        assertEquals(repository.commitRequests[0].second, repository.commitRequests[1].second)
        val completed = requireNotNull(viewModel.uiState.legacyMigration)
        assertEquals(listOf("legacy"), completed.migrated.map { it.id })
        assertTrue(completed.plan.conflicts.isEmpty())
        assertTrue(reviews.reviewed)
        scope.cancel()
    }
}

private fun viewModel(
    repository: FakePlatformRepository,
    scope: CoroutineScope,
    legacyBindings: List<LightBinding> = emptyList(),
    reviews: FakeMigrationReviewStore = FakeMigrationReviewStore()
) = LightFindingViewModel(
    configStore = FakePlatformConfigStore(),
    repository = repository,
    enrollmentManager = ApprovedEnrollmentController(),
    legacyBindingRepository = InMemoryLightBindingRepository(legacyBindings),
    legacyMigrationReviews = reviews,
    runtimeCloser = repository::stopRealtime,
    externalScope = scope,
    pollSleeper = {},
    autoConnect = true
)

private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

private class FakePlatformConfigStore : PlatformConfigStore {
    private var url = PlatformUrlValidator.DEFAULT_SERVER_URL

    override fun currentServerUrl(): String = url

    override fun currentEndpoint(): PlatformEndpoint =
        PlatformUrlValidator.requireForWifiProduction(url)

    override fun updateServerUrl(rawUrl: String): PlatformUrlValidation {
        val result = PlatformUrlValidator.validateForWifiProduction(rawUrl)
        if (result is PlatformUrlValidation.Valid) {
            url = result.endpoint.canonicalServerUrl
        }
        return result
    }

    override fun resetToDefault() {
        url = PlatformUrlValidator.DEFAULT_SERVER_URL
    }
}

private class ApprovedEnrollmentController : DeviceEnrollmentController {
    override val state: StateFlow<EnrollmentClientState> =
        MutableStateFlow(EnrollmentClientState.Approved)

    override suspend fun beginEnrollment(): DeviceEnrollmentCreated =
        error("Enrollment must not restart for approved test credentials.")

    override suspend fun pollEnrollment(): DeviceEnrollmentState =
        error("Approved credentials must not poll enrollment.")
}

private class FakeMigrationReviewStore : LegacyBindingMigrationReviewStore {
    var reviewed = false

    override fun isReviewed(serverUrl: String): Boolean = reviewed

    override fun markReviewed(serverUrl: String) {
        reviewed = true
    }
}

private class FakePlatformRepository(
    remoteBindings: List<Binding> = emptyList(),
    remoteTags: List<Tag> = emptyList(),
    private val unavailable: Boolean = false,
    snapshotsStale: Boolean = false,
    initialSnapshotsSynchronized: Boolean = true,
    staleCommitOnce: Boolean = false,
    transportFailureOnce: Boolean = false,
    private val previewProvider: (
        AndroidBindingMigrationPreviewRequest,
        Int
    ) -> Map<String, AndroidBindingMigrationClassification> = { request, _ ->
        defaultPreviewClassifications(request)
    }
) : PlatformRepository {
    private val initialSynchronizedAt = NOW.takeIf { initialSnapshotsSynchronized }
    private var shouldReturnStale = staleCommitOnce
    private var shouldFailTransport = transportFailureOnce
    private val previewsByToken = mutableMapOf<String, AndroidBindingMigrationPreview>()
    private val mutableAccess = MutableStateFlow(
        PlatformAccessState(
            apiAvailability = PlatformApiAvailability.UNKNOWN,
            deviceAuthorization = PlatformDeviceAuthorization.APPROVED
        )
    )
    private val mutableBindings = MutableStateFlow(
        PlatformSnapshot(
            value = remoteBindings,
            synchronizedAt = initialSynchronizedAt,
            isStale = snapshotsStale
        )
    )
    private val mutableProducts = MutableStateFlow(
        PlatformSnapshot<List<Product>>(
            value = emptyList(),
            synchronizedAt = initialSynchronizedAt,
            isStale = snapshotsStale
        )
    )
    private val mutableTags = MutableStateFlow(
        PlatformSnapshot(
            value = remoteTags,
            synchronizedAt = initialSynchronizedAt,
            isStale = snapshotsStale
        )
    )

    override val accessState: StateFlow<PlatformAccessState> = mutableAccess
    override val brokerStatus: StateFlow<PlatformSnapshot<BrokerStatus?>> =
        MutableStateFlow(PlatformSnapshot(readyBrokerStatus(), NOW, isStale = false))
    override val products: StateFlow<PlatformSnapshot<List<Product>>> = mutableProducts
    override val bindings: StateFlow<PlatformSnapshot<List<Binding>>> = mutableBindings
    override val tags: StateFlow<PlatformSnapshot<List<Tag>>> = mutableTags
    override val stations: StateFlow<PlatformSnapshot<List<Station>>> =
        MutableStateFlow(PlatformSnapshot(emptyList(), NOW, isStale = false))
    override val commands: StateFlow<Map<UUID, LightCommand>> = MutableStateFlow(emptyMap())
    override val eventConnectionState: StateFlow<PlatformEventConnectionState> =
        MutableStateFlow(PlatformEventConnectionState.Stopped)
    override val events: SharedFlow<PlatformEvent> = MutableSharedFlow()

    val lightCommands = mutableListOf<LightCommandRequest>()
    val recordWrites = mutableListOf<String>()
    val previewRequests = mutableListOf<AndroidBindingMigrationPreviewRequest>()
    val commitRequests = mutableListOf<Pair<AndroidBindingMigrationCommitRequest, IdempotencyKey>>()
    val stationRefreshStates = mutableListOf<Boolean>()
    private val idempotencyKeys = IdempotencyKeyFactory()

    override fun startRealtime() {
        mutableAccess.value = PlatformAccessState(
            apiAvailability = PlatformApiAvailability.CHECKING,
            deviceAuthorization = PlatformDeviceAuthorization.APPROVED
        )
        mutableAccess.value = PlatformAccessState(
            apiAvailability = if (unavailable) {
                PlatformApiAvailability.UNAVAILABLE
            } else {
                PlatformApiAvailability.AVAILABLE
            },
            deviceAuthorization = PlatformDeviceAuthorization.APPROVED,
            lastVerifiedAt = NOW
        )
    }

    override fun stopRealtime() = Unit

    fun publishFreshSnapshots() {
        mutableProducts.value = mutableProducts.value.copy(
            synchronizedAt = NOW,
            isStale = false
        )
        mutableBindings.value = mutableBindings.value.copy(
            synchronizedAt = NOW,
            isStale = false
        )
        mutableTags.value = mutableTags.value.copy(
            synchronizedAt = NOW,
            isStale = false
        )
    }

    override fun setStationStatusRefreshActive(active: Boolean) {
        stationRefreshStates += active
    }

    override fun newWriteIdempotencyKey(): IdempotencyKey = idempotencyKeys.create()

    override suspend fun refreshAll() = Unit
    override suspend fun refreshProducts() = Unit
    override suspend fun refreshBrokerStatus() = Unit
    override suspend fun refreshBindings() = Unit
    override suspend fun refreshTags() = Unit
    override suspend fun refreshStations() = Unit

    override suspend fun bind(
        request: BindingCreateRequest,
        idempotencyKey: IdempotencyKey?
    ): Binding {
        recordWrites += "bind:${request.productCode}:${request.tagId}"
        return remoteBinding(
            idSeed = "created:${request.productCode}:${request.tagId}",
            productCode = request.productCode,
            tagId = request.tagId
        )
    }

    override suspend fun rebind(
        bindingId: UUID,
        request: RebindRequest,
        idempotencyKey: IdempotencyKey?
    ): RebindResult = error("Not used.")

    override suspend fun unbind(
        bindingId: UUID,
        idempotencyKey: IdempotencyKey?
    ): Binding = error("Not used.")

    override suspend fun previewAndroidBindingMigration(
        request: AndroidBindingMigrationPreviewRequest
    ): AndroidBindingMigrationPreview {
        previewRequests += request
        val preview = migrationPreview(
            request = request,
            classifications = previewProvider(request, previewRequests.size),
            sequence = previewRequests.size
        )
        previewsByToken[preview.previewToken] = preview
        return preview
    }

    override suspend fun commitAndroidBindingMigration(
        request: AndroidBindingMigrationCommitRequest,
        idempotencyKey: IdempotencyKey
    ): AndroidBindingMigrationCommitResult {
        commitRequests += request to idempotencyKey
        if (shouldReturnStale) {
            shouldReturnStale = false
            throw PlatformApiException(
                statusCode = 409,
                apiError = ApiError(
                    code = "MIGRATION_PREVIEW_STALE",
                    message = "Authoritative state changed.",
                    details = emptyList(),
                    requestId = UUID.fromString("22ed41af-3cca-4f31-829c-2ee9151f9adc")
                ),
                retryAfterSeconds = null
            )
        }
        if (shouldFailTransport) {
            shouldFailTransport = false
            throw PlatformTransportException(IOException("response lost"))
        }
        val preview = requireNotNull(previewsByToken[request.previewToken])
        return migrationCommit(request, preview)
    }

    override suspend fun registerTag(
        request: TagRegisterRequest,
        idempotencyKey: IdempotencyKey?
    ): Tag {
        recordWrites += "register:${request.tagId}"
        return tag(request.tagId, request.stationId)
    }

    override suspend fun sendLightCommand(
        request: LightCommandRequest,
        idempotencyKey: IdempotencyKey?
    ): LightCommand {
        lightCommands += request
        val targetCount = request.productCode?.let { productCode ->
            mutableBindings.value.value.count { it.productCode == productCode }
        } ?: request.tagIds.orEmpty().size
        return lightCommand(request, targetCount)
    }

    override suspend fun allOff(
        stationId: String,
        idempotencyKey: IdempotencyKey?
    ): LightCommand = error("Not used.")

    override suspend fun command(commandId: UUID): LightCommand = error("Not used.")
}

private fun defaultPreviewClassifications(
    request: AndroidBindingMigrationPreviewRequest
): Map<String, AndroidBindingMigrationClassification> {
    val duplicateTags = request.records.groupingBy { it.tagId }.eachCount()
        .filterValues { it > 1 }
        .keys
    return request.records.associate { record ->
        record.clientRecordKey to if (record.tagId in duplicateTags) {
            AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG
        } else {
            AndroidBindingMigrationClassification.MIGRATABLE
        }
    }
}

private fun migrationPreview(
    request: AndroidBindingMigrationPreviewRequest,
    classifications: Map<String, AndroidBindingMigrationClassification>,
    sequence: Int
): AndroidBindingMigrationPreview {
    val records = request.records.map { record ->
        AndroidBindingMigrationPreviewRecord(
            clientRecordKey = record.clientRecordKey,
            productCode = record.productCode,
            productName = record.productName,
            tagId = record.tagId,
            stationId = record.stationId,
            classification = requireNotNull(classifications[record.clientRecordKey]),
            authoritativeBindingId = null,
            authoritativeProductCode = if (
                classifications[record.clientRecordKey] ==
                AndroidBindingMigrationClassification.TAG_BOUND_TO_DIFFERENT_PRODUCT
            ) {
                "OTHER-PRODUCT"
            } else {
                null
            },
            authoritativeStationId = null
        )
    }
    return AndroidBindingMigrationPreview(
        previewToken = "${"a".repeat(32)}.${sequence.toString().padStart(43, 'b')}",
        summary = AndroidBindingMigrationPreviewSummary(
            totalRecords = records.size,
            migratable = records.count {
                it.classification == AndroidBindingMigrationClassification.MIGRATABLE
            },
            identical = records.count {
                it.classification == AndroidBindingMigrationClassification.IDENTICAL
            },
            duplicateLegacyTag = records.count {
                it.classification == AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG
            },
            tagBoundToDifferentProduct = records.count {
                it.classification ==
                    AndroidBindingMigrationClassification.TAG_BOUND_TO_DIFFERENT_PRODUCT
            },
            stationMismatch = records.count {
                it.classification == AndroidBindingMigrationClassification.STATION_MISMATCH
            },
            stationNotFound = records.count {
                it.classification == AndroidBindingMigrationClassification.STATION_NOT_FOUND
            }
        ),
        records = records
    )
}

private fun migrationCommit(
    request: AndroidBindingMigrationCommitRequest,
    preview: AndroidBindingMigrationPreview
): AndroidBindingMigrationCommitResult {
    val requestByKey = request.records.associateBy { it.clientRecordKey }
    val selected = request.selectedDuplicateKeys.toSet()
    val commitRecords = preview.records.map { record ->
        val outcome = when (record.classification) {
            AndroidBindingMigrationClassification.MIGRATABLE ->
                AndroidBindingMigrationOutcome.MIGRATED
            AndroidBindingMigrationClassification.IDENTICAL ->
                AndroidBindingMigrationOutcome.IDENTICAL
            AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG ->
                if (record.clientRecordKey in selected) {
                    AndroidBindingMigrationOutcome.MIGRATED
                } else {
                    AndroidBindingMigrationOutcome.SKIPPED
                }
            else -> AndroidBindingMigrationOutcome.SKIPPED
        }
        AndroidBindingMigrationCommitRecord(
            clientRecordKey = record.clientRecordKey,
            classification = record.classification,
            outcome = outcome,
            bindingId = when (outcome) {
                AndroidBindingMigrationOutcome.MIGRATED,
                AndroidBindingMigrationOutcome.IDENTICAL ->
                    UUID.nameUUIDFromBytes("binding:${record.clientRecordKey}".toByteArray())
                AndroidBindingMigrationOutcome.SKIPPED -> null
            }
        )
    }
    val migrated = commitRecords.filter { it.outcome == AndroidBindingMigrationOutcome.MIGRATED }
    val bindings = migrated.map { outcome ->
        migrationBinding(
            requireNotNull(requestByKey[outcome.clientRecordKey]),
            requireNotNull(outcome.bindingId)
        )
    }
    return AndroidBindingMigrationCommitResult(
        committedAt = NOW,
        summary = AndroidBindingMigrationCommitSummary(
            totalRecords = commitRecords.size,
            migratedRecords = migrated.size,
            identicalRecords = commitRecords.count {
                it.outcome == AndroidBindingMigrationOutcome.IDENTICAL
            },
            skippedRecords = commitRecords.count {
                it.outcome == AndroidBindingMigrationOutcome.SKIPPED
            },
            createdProducts = migrated.size,
            createdTags = migrated.size,
            createdBindings = migrated.size
        ),
        records = commitRecords,
        bindings = bindings
    )
}

private fun migrationBinding(record: AndroidBindingMigrationRecord, bindingId: UUID) = Binding(
    id = bindingId,
    productId = UUID.nameUUIDFromBytes(record.productCode.toByteArray()),
    productCode = record.productCode,
    productName = record.productName,
    tagId = record.tagId,
    siteId = SITE_ID,
    stationId = record.stationId,
    source = BindingSource.MIGRATION,
    actorType = ActorType.ANDROID,
    actorId = "android-device",
    actorDisplayName = "Receiving Phone",
    boundAt = NOW,
    unboundAt = null,
    isActive = true
)

private fun remoteBinding(idSeed: String, productCode: String, tagId: String) = Binding(
    id = UUID.nameUUIDFromBytes(idSeed.toByteArray()),
    productId = UUID.nameUUIDFromBytes(productCode.toByteArray()),
    productCode = productCode,
    productName = productCode,
    tagId = tagId,
    siteId = SITE_ID,
    stationId = STATION_ID,
    source = BindingSource.ANDROID,
    actorType = ActorType.ANDROID,
    actorId = "android-device",
    actorDisplayName = "Receiving Phone",
    boundAt = NOW,
    unboundAt = null,
    isActive = true
)

private fun legacy(id: String, productCode: String, tagId: String) = LightBinding(
    id = id,
    itemCode = productCode,
    itemName = productCode,
    tagId = tagId,
    stationId = STATION_ID,
    createdAtMillis = NOW.toEpochMilli(),
    updatedAtMillis = NOW.toEpochMilli()
)

private fun tag(tagId: String, stationId: String) = Tag(
    tagId = tagId,
    siteId = SITE_ID,
    stationId = stationId,
    registeredAt = NOW,
    firstSeenAt = NOW,
    lastSeenAt = NOW,
    online = false,
    batteryRaw = null,
    batteryVoltage = null,
    batteryLevel = null,
    lowBattery = false,
    firmwareVersion = null,
    groupNo = null,
    lastResultType = null,
    isAbnormal = false,
    abnormalReason = null,
    activeBindingId = null
)

private fun lightCommand(request: LightCommandRequest, targetCount: Int) = LightCommand(
    id = UUID.nameUUIDFromBytes(request.toString().toByteArray()),
    action = request.action,
    productId = request.productCode?.let { UUID.nameUUIDFromBytes(it.toByteArray()) },
    productCode = request.productCode,
    requestedColor = request.color,
    status = CommandStatus.ACCEPTED,
    targetCount = targetCount,
    confirmedCount = 0,
    unconfirmedCount = 0,
    failedCount = 0,
    createdAt = NOW,
    publishedAt = null,
    completedAt = null,
    items = emptyList()
)

private fun readyBrokerStatus() = BrokerStatus(
    serviceName = "HighTacMqttBroker",
    serviceState = BrokerServiceState.RUNNING,
    endpoint = "192.168.1.105:1884",
    tcpReachable = true,
    mqttConnected = true,
    subscriptionsReady = true,
    startedAt = NOW.minusSeconds(60),
    uptimeSeconds = 60,
    checkedAt = NOW
)

private val NOW = Instant.parse("2026-07-16T08:15:30Z")
private val SITE_ID = UUID.fromString("2b16ae29-f7ab-4c44-9fc6-a0ca693d9c68")
private const val STATION_ID = "90A9F1234567"
