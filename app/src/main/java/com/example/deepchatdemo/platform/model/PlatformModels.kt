package com.example.deepchatdemo.platform.model

import java.time.Instant
import java.util.UUID

class SensitiveString private constructor(
    private val rawValue: String
) {
    fun <T> use(block: (String) -> T): T = block(rawValue)

    val length: Int
        get() = rawValue.length

    override fun toString(): String = REDACTED

    companion object {
        private const val REDACTED = "[REDACTED]"

        fun fromTransport(value: String): SensitiveString {
            require(value.isNotBlank()) { "Sensitive value must not be blank." }
            return SensitiveString(value)
        }
    }
}

enum class CheckStatus {
    READY,
    DEGRADED,
    NOT_READY
}

enum class ReadinessCheckName(val wireName: String) {
    DATABASE("database"),
    MIGRATIONS("migrations"),
    MQTT_BRIDGE("mqtt_bridge")
}

data class Liveness(
    val status: String,
    val service: String,
    val version: String,
    val checkedAt: Instant
)

data class ReadinessCheck(
    val name: ReadinessCheckName,
    val status: CheckStatus,
    val message: String,
    val checkedAt: Instant
)

data class Readiness(
    val status: CheckStatus,
    val checks: List<ReadinessCheck>,
    val checkedAt: Instant
)

data class Pagination(
    val page: Int,
    val pageSize: Int,
    val totalItems: Long,
    val totalPages: Int
)

data class Page<T>(
    val items: List<T>,
    val pagination: Pagination
)

data class PageRequest(
    val page: Int = 1,
    val pageSize: Int = 50,
    val sort: String? = null
) {
    init {
        require(page >= 1) { "Page must be at least 1." }
        require(pageSize in SUPPORTED_PAGE_SIZES) { "Unsupported page size." }
        require(sort == null || sort.length <= 256) { "Sort is too long." }
    }

    companion object {
        val SUPPORTED_PAGE_SIZES = setOf(20, 50, 100)
    }
}

data class ApiErrorDetail(
    val field: String?,
    val code: String,
    val message: String
)

data class ApiError(
    val code: String,
    val message: String,
    val details: List<ApiErrorDetail>,
    val requestId: UUID
)

data class DeviceIdentity(
    val fingerprintHash: String,
    val signingCertificateDigest: String
)

data class AndroidDeviceMetadata(
    val manufacturer: String,
    val model: String,
    val appVersion: String
)

enum class EnrollmentStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}

enum class DeviceStatus {
    PENDING,
    APPROVED,
    REVOKED
}

data class DeviceEnrollmentRequest(
    val fingerprintHash: String,
    val installationKeyHash: String,
    val manufacturer: String,
    val model: String,
    val appVersion: String
)

data class DeviceEnrollmentCreated(
    val id: UUID,
    val status: EnrollmentStatus,
    val pollSecret: SensitiveString?,
    val expiresAt: Instant,
    val pollAfterSeconds: Int?,
    val displayName: String? = null,
    val deviceId: UUID? = null,
    val deviceToken: SensitiveString? = null
)

data class DeviceEnrollmentState(
    val id: UUID,
    val status: EnrollmentStatus,
    val displayName: String?,
    val deviceId: UUID?,
    val deviceToken: SensitiveString?,
    val expiresAt: Instant
)

data class AndroidDevice(
    val id: UUID,
    val displayName: String?,
    val manufacturer: String,
    val model: String,
    val appVersion: String,
    val status: DeviceStatus,
    val online: Boolean,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant?,
    val approvedAt: Instant?,
    val revokedAt: Instant?
)

enum class ProductSource {
    BINDING,
    EXCEL
}

data class Product(
    val id: UUID,
    val productCode: String,
    val productName: String?,
    val source: ProductSource,
    val isActive: Boolean,
    val activeBindingCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ProductDetail(
    val product: Product,
    val activeBindings: List<Binding>
)

enum class BindingSource {
    ANDROID,
    WEB,
    MIGRATION
}

enum class ActorType {
    ADMIN,
    ANDROID,
    SYSTEM
}

data class BindingCreateRequest(
    val productCode: String,
    val productName: String?,
    val tagId: String,
    val stationId: String
)

data class Binding(
    val id: UUID,
    val productId: UUID,
    val productCode: String,
    val productName: String?,
    val tagId: String,
    val siteId: UUID,
    val stationId: String,
    val source: BindingSource,
    val actorType: ActorType,
    val actorId: String,
    val actorDisplayName: String,
    val boundAt: Instant,
    val unboundAt: Instant?,
    val isActive: Boolean
)

data class RebindRequest(
    val productCode: String,
    val productName: String?,
    val expectedTagId: String
)

data class RebindResult(
    val removedBinding: Binding,
    val createdBinding: Binding
)

data class AndroidBindingMigrationRecord(
    val clientRecordKey: String,
    val productCode: String,
    val productName: String?,
    val tagId: String,
    val stationId: String
)

data class AndroidBindingMigrationPreviewRequest(
    val records: List<AndroidBindingMigrationRecord>
)

enum class AndroidBindingMigrationClassification {
    MIGRATABLE,
    IDENTICAL,
    DUPLICATE_LEGACY_TAG,
    TAG_BOUND_TO_DIFFERENT_PRODUCT,
    STATION_MISMATCH,
    STATION_NOT_FOUND
}

data class AndroidBindingMigrationPreviewRecord(
    val clientRecordKey: String,
    val productCode: String,
    val productName: String?,
    val tagId: String,
    val stationId: String,
    val classification: AndroidBindingMigrationClassification,
    val authoritativeBindingId: UUID?,
    val authoritativeProductCode: String?,
    val authoritativeStationId: String?
)

data class AndroidBindingMigrationPreviewSummary(
    val totalRecords: Int,
    val migratable: Int,
    val identical: Int,
    val duplicateLegacyTag: Int,
    val tagBoundToDifferentProduct: Int,
    val stationMismatch: Int,
    val stationNotFound: Int
)

data class AndroidBindingMigrationPreview(
    val previewToken: String,
    val summary: AndroidBindingMigrationPreviewSummary,
    val records: List<AndroidBindingMigrationPreviewRecord>
)

data class AndroidBindingMigrationCommitRequest(
    val records: List<AndroidBindingMigrationRecord>,
    val previewToken: String,
    val selectedDuplicateKeys: List<String>
)

enum class AndroidBindingMigrationOutcome {
    MIGRATED,
    IDENTICAL,
    SKIPPED
}

data class AndroidBindingMigrationCommitRecord(
    val clientRecordKey: String,
    val classification: AndroidBindingMigrationClassification,
    val outcome: AndroidBindingMigrationOutcome,
    val bindingId: UUID?
)

data class AndroidBindingMigrationCommitSummary(
    val totalRecords: Int,
    val migratedRecords: Int,
    val identicalRecords: Int,
    val skippedRecords: Int,
    val createdProducts: Int,
    val createdTags: Int,
    val createdBindings: Int
)

data class AndroidBindingMigrationCommitResult(
    val committedAt: Instant,
    val summary: AndroidBindingMigrationCommitSummary,
    val records: List<AndroidBindingMigrationCommitRecord>,
    val bindings: List<Binding>
)

enum class StationStatus {
    UNKNOWN,
    ONLINE,
    STALE,
    OFFLINE
}

data class Station(
    val stationId: String,
    val siteId: UUID,
    val alias: String?,
    val status: StationStatus,
    val mac: String?,
    val firmwareVersion: String?,
    val serverAddress: String?,
    val heartbeatSeconds: Int?,
    val lastHeartbeatAt: Instant?,
    val totalCount: Int,
    val sendCount: Int,
    val brokerConnected: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Tag(
    val tagId: String,
    val siteId: UUID?,
    val stationId: String?,
    val registeredAt: Instant?,
    val firstSeenAt: Instant?,
    val lastSeenAt: Instant?,
    val online: Boolean,
    val batteryRaw: Int?,
    val batteryVoltage: Double?,
    val batteryLevel: Int?,
    val lowBattery: Boolean,
    val firmwareVersion: String?,
    val groupNo: Int?,
    val lastResultType: Int?,
    val isAbnormal: Boolean,
    val abnormalReason: String?,
    val activeBindingId: UUID?
)

data class TagStatusHistoryItem(
    val id: UUID,
    val occurredAt: Instant,
    val online: Boolean,
    val batteryRaw: Int?,
    val batteryLevel: Int?,
    val resultType: Int?,
    val isAbnormal: Boolean,
    val abnormalReason: String?
)

data class TagDetail(
    val tag: Tag,
    val activeBinding: Binding?,
    val recentHistory: List<TagStatusHistoryItem>
)

data class TagRegisterRequest(
    val tagId: String,
    val stationId: String
)

enum class LightAction {
    LIGHT_ON,
    LIGHT_OFF
}

enum class LightColor {
    RED,
    GREEN,
    BLUE,
    CYAN,
    PINK
}

enum class CommandStatus {
    ACCEPTED,
    PUBLISHED,
    CONFIRMED,
    PARTIALLY_CONFIRMED,
    UNCONFIRMED,
    FAILED,
    SUPERSEDED
}

enum class CommandItemStatus {
    PENDING,
    PUBLISHED,
    CONFIRMED,
    UNCONFIRMED,
    FAILED,
    SUPERSEDED
}

enum class CommandCorrelation {
    NONE,
    HEURISTIC_STATION_TAG_TIME,
    HEURISTIC_STATE_MATCH
}

data class LightCommandRequest(
    val action: LightAction,
    val productCode: String? = null,
    val tagIds: List<String>? = null,
    val color: LightColor? = null
) {
    init {
        require((productCode != null) xor (tagIds != null)) {
            "Exactly one command target must be supplied."
        }
        require(tagIds == null || tagIds.isNotEmpty()) { "Tag target must not be empty." }
        require(tagIds == null || tagIds.size <= 2_000) { "Too many tag targets." }
        require(tagIds == null || tagIds.distinct().size == tagIds.size) {
            "Tag targets must be unique."
        }
        require((action == LightAction.LIGHT_ON) == (color != null)) {
            "LIGHT_ON requires a color and LIGHT_OFF forbids one."
        }
    }

    companion object {
        fun forProduct(
            action: LightAction,
            productCode: String,
            color: LightColor? = null
        ): LightCommandRequest = LightCommandRequest(
            action = action,
            productCode = productCode,
            color = color
        )

        fun forTags(
            action: LightAction,
            tagIds: List<String>,
            color: LightColor? = null
        ): LightCommandRequest = LightCommandRequest(
            action = action,
            tagIds = tagIds,
            color = color
        )
    }
}

data class CommandItem(
    val id: UUID,
    val tagId: String,
    val stationId: String,
    val status: CommandItemStatus,
    val publishAttempts: Int,
    val publishedAt: Instant?,
    val confirmedAt: Instant?,
    val lastResultType: Int?,
    val correlation: CommandCorrelation,
    val failureCode: String?
)

data class LightCommand(
    val id: UUID,
    val action: LightAction,
    val productId: UUID?,
    val productCode: String?,
    val requestedColor: LightColor?,
    val status: CommandStatus,
    val targetCount: Int,
    val confirmedCount: Int,
    val unconfirmedCount: Int,
    val failedCount: Int,
    val createdAt: Instant,
    val publishedAt: Instant?,
    val completedAt: Instant?,
    val items: List<CommandItem>
)

enum class BrokerServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
    UNKNOWN
}

data class BrokerStatus(
    val serviceName: String,
    val serviceState: BrokerServiceState,
    val endpoint: String,
    val tcpReachable: Boolean,
    val mqttConnected: Boolean,
    val subscriptionsReady: Boolean,
    val startedAt: Instant?,
    val uptimeSeconds: Long?,
    val checkedAt: Instant
) {
    val isReady: Boolean
        get() = serviceState == BrokerServiceState.RUNNING &&
            tcpReachable &&
            mqttConnected &&
            subscriptionsReady
}

enum class EventEntityType(val wireName: String) {
    BROKER("broker"),
    STATION("station"),
    TAG("tag"),
    BINDING("binding"),
    COMMAND("command"),
    DEVICE("device"),
    SYSTEM("system")
}

sealed interface PlatformEvent {
    val eventId: UUID
    val schemaVersion: Int
    val occurredAt: Instant
    val entityType: EventEntityType
    val entityId: String
}

data class BrokerStatusChangedPayload(
    val previousStatus: BrokerServiceState,
    val currentStatus: BrokerServiceState,
    val windowsServiceRunning: Boolean,
    val tcpReachable: Boolean,
    val mqttConnected: Boolean,
    val subscriptionsReady: Boolean,
    val endpoint: String,
    val reason: String?
)

data class BrokerStatusChangedEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: BrokerStatusChangedPayload
) : PlatformEvent

data class StationStatusChangedPayload(
    val stationId: String,
    val previousStatus: StationStatus,
    val currentStatus: StationStatus,
    val lastHeartbeatAt: Instant?,
    val brokerConnected: Boolean,
    val reason: String?
)

data class StationStatusChangedEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: StationStatusChangedPayload
) : PlatformEvent

data class StationHeartbeatPayload(
    val stationId: String,
    val status: StationStatus,
    val mac: String,
    val alias: String?,
    val serverAddress: String,
    val heartbeatSeconds: Int,
    val firmwareVersion: String,
    val totalCount: Int,
    val sendCount: Int
)

data class StationHeartbeatEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: StationHeartbeatPayload
) : PlatformEvent

data class TagStatusChangedPayload(
    val tagId: String,
    val stationId: String?,
    val online: Boolean,
    val lastSeenAt: Instant?,
    val batteryRaw: Int?,
    val batteryVoltage: Double?,
    val batteryLevel: Int?,
    val lowBattery: Boolean,
    val isAbnormal: Boolean,
    val abnormalReason: String?,
    val lastResultType: Int?
)

data class TagStatusChangedEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: TagStatusChangedPayload
) : PlatformEvent

data class BindingSnapshot(
    val bindingId: UUID,
    val productId: UUID,
    val productCode: String,
    val productName: String?,
    val tagId: String,
    val siteId: UUID,
    val stationId: String,
    val source: BindingSource,
    val actorType: ActorType,
    val actorId: String
)

data class BindingCreatedPayload(
    val binding: BindingSnapshot,
    val boundAt: Instant
)

data class BindingCreatedEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: BindingCreatedPayload
) : PlatformEvent

data class BindingRemovedPayload(
    val binding: BindingSnapshot,
    val unboundAt: Instant,
    val replacementBindingId: UUID?
)

data class BindingRemovedEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: BindingRemovedPayload
) : PlatformEvent

data class CommandItemUpdate(
    val itemId: UUID,
    val tagId: String,
    val stationId: String,
    val previousStatus: CommandItemStatus,
    val currentStatus: CommandItemStatus,
    val correlation: CommandCorrelation,
    val resultType: Int?
)

data class CommandStatusChangedPayload(
    val commandId: UUID,
    val action: LightAction,
    val previousStatus: CommandStatus,
    val currentStatus: CommandStatus,
    val targetCount: Int,
    val confirmedCount: Int,
    val unconfirmedCount: Int,
    val failedCount: Int,
    val item: CommandItemUpdate?
)

data class CommandStatusChangedEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: CommandStatusChangedPayload
) : PlatformEvent

data class DeviceStatusChangedPayload(
    val deviceId: UUID,
    val displayName: String?,
    val previousStatus: DeviceStatus,
    val currentStatus: DeviceStatus,
    val manufacturer: String,
    val model: String,
    val appVersion: String,
    val reason: String?
)

data class DeviceStatusChangedEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: DeviceStatusChangedPayload
) : PlatformEvent

enum class NoticeSeverity {
    INFO,
    WARNING,
    ERROR
}

data class SystemNoticePayload(
    val severity: NoticeSeverity,
    val code: String,
    val message: String,
    val resourceType: String?,
    val resourceId: String?
)

data class SystemNoticeEvent(
    override val eventId: UUID,
    override val schemaVersion: Int,
    override val occurredAt: Instant,
    override val entityType: EventEntityType,
    override val entityId: String,
    val payload: SystemNoticePayload
) : PlatformEvent
