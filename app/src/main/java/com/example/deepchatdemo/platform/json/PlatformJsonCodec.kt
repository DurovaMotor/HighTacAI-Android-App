package com.example.deepchatdemo.platform.json

import com.example.deepchatdemo.platform.model.ActorType
import com.example.deepchatdemo.platform.model.AndroidDevice
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
import com.example.deepchatdemo.platform.model.ApiErrorDetail
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingCreateRequest
import com.example.deepchatdemo.platform.model.BindingCreatedEvent
import com.example.deepchatdemo.platform.model.BindingCreatedPayload
import com.example.deepchatdemo.platform.model.BindingRemovedEvent
import com.example.deepchatdemo.platform.model.BindingRemovedPayload
import com.example.deepchatdemo.platform.model.BindingSnapshot
import com.example.deepchatdemo.platform.model.BindingSource
import com.example.deepchatdemo.platform.model.BrokerServiceState
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.BrokerStatusChangedEvent
import com.example.deepchatdemo.platform.model.BrokerStatusChangedPayload
import com.example.deepchatdemo.platform.model.CheckStatus
import com.example.deepchatdemo.platform.model.CommandCorrelation
import com.example.deepchatdemo.platform.model.CommandItem
import com.example.deepchatdemo.platform.model.CommandItemStatus
import com.example.deepchatdemo.platform.model.CommandItemUpdate
import com.example.deepchatdemo.platform.model.CommandStatus
import com.example.deepchatdemo.platform.model.CommandStatusChangedEvent
import com.example.deepchatdemo.platform.model.CommandStatusChangedPayload
import com.example.deepchatdemo.platform.model.DeviceEnrollmentCreated
import com.example.deepchatdemo.platform.model.DeviceEnrollmentRequest
import com.example.deepchatdemo.platform.model.DeviceEnrollmentState
import com.example.deepchatdemo.platform.model.DeviceStatus
import com.example.deepchatdemo.platform.model.DeviceStatusChangedEvent
import com.example.deepchatdemo.platform.model.DeviceStatusChangedPayload
import com.example.deepchatdemo.platform.model.EnrollmentStatus
import com.example.deepchatdemo.platform.model.EventEntityType
import com.example.deepchatdemo.platform.model.LightAction
import com.example.deepchatdemo.platform.model.LightColor
import com.example.deepchatdemo.platform.model.LightCommand
import com.example.deepchatdemo.platform.model.LightCommandRequest
import com.example.deepchatdemo.platform.model.Liveness
import com.example.deepchatdemo.platform.model.NoticeSeverity
import com.example.deepchatdemo.platform.model.Page
import com.example.deepchatdemo.platform.model.Pagination
import com.example.deepchatdemo.platform.model.PlatformEvent
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.ProductDetail
import com.example.deepchatdemo.platform.model.ProductSource
import com.example.deepchatdemo.platform.model.Readiness
import com.example.deepchatdemo.platform.model.ReadinessCheck
import com.example.deepchatdemo.platform.model.ReadinessCheckName
import com.example.deepchatdemo.platform.model.RebindRequest
import com.example.deepchatdemo.platform.model.RebindResult
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.StationHeartbeatEvent
import com.example.deepchatdemo.platform.model.StationHeartbeatPayload
import com.example.deepchatdemo.platform.model.StationStatus
import com.example.deepchatdemo.platform.model.StationStatusChangedEvent
import com.example.deepchatdemo.platform.model.StationStatusChangedPayload
import com.example.deepchatdemo.platform.model.SystemNoticeEvent
import com.example.deepchatdemo.platform.model.SystemNoticePayload
import com.example.deepchatdemo.platform.model.Tag
import com.example.deepchatdemo.platform.model.TagDetail
import com.example.deepchatdemo.platform.model.TagRegisterRequest
import com.example.deepchatdemo.platform.model.TagStatusChangedEvent
import com.example.deepchatdemo.platform.model.TagStatusChangedPayload
import com.example.deepchatdemo.platform.model.TagStatusHistoryItem
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object PlatformJsonCodec {
    fun parseLiveness(json: String): Liveness = parseLiveness(StrictJson.parseObject(json))

    fun parseReadiness(json: String): Readiness = parseReadiness(StrictJson.parseObject(json))

    fun parseBrokerStatus(json: String): BrokerStatus =
        parseBrokerStatus(StrictJson.parseObject(json))

    fun parseDeviceEnrollmentCreated(json: String): DeviceEnrollmentCreated =
        parseDeviceEnrollmentCreated(StrictJson.parseObject(json))

    fun parseDeviceEnrollmentState(json: String): DeviceEnrollmentState =
        parseDeviceEnrollmentState(StrictJson.parseObject(json))

    fun parseAndroidDevice(json: String): AndroidDevice =
        parseAndroidDevice(StrictJson.parseObject(json))

    fun parseProduct(json: String): Product = parseProduct(StrictJson.parseObject(json))

    fun parseProductDetail(json: String): ProductDetail =
        parseProductDetail(StrictJson.parseObject(json))

    fun parseProductPage(json: String): Page<Product> = parsePage(
        StrictJson.parseObject(json),
        ::parseProduct
    )

    fun parseBinding(json: String): Binding = parseBinding(StrictJson.parseObject(json))

    fun parseBindingPage(json: String): Page<Binding> = parsePage(
        StrictJson.parseObject(json),
        ::parseBinding
    )

    fun parseRebindResult(json: String): RebindResult =
        parseRebindResult(StrictJson.parseObject(json))

    fun parseAndroidBindingMigrationPreview(json: String): AndroidBindingMigrationPreview =
        parseAndroidBindingMigrationPreview(StrictJson.parseObject(json))

    fun parseAndroidBindingMigrationCommit(json: String): AndroidBindingMigrationCommitResult =
        parseAndroidBindingMigrationCommit(StrictJson.parseObject(json))

    fun parseStation(json: String): Station = parseStation(StrictJson.parseObject(json))

    fun parseStationPage(json: String): Page<Station> = parsePage(
        StrictJson.parseObject(json),
        ::parseStation
    )

    fun parseTag(json: String): Tag = parseTag(StrictJson.parseObject(json))

    fun parseTagDetail(json: String): TagDetail = parseTagDetail(StrictJson.parseObject(json))

    fun parseTagPage(json: String): Page<Tag> = parsePage(
        StrictJson.parseObject(json),
        ::parseTag
    )

    fun parseLightCommand(json: String): LightCommand =
        parseLightCommand(StrictJson.parseObject(json))

    fun parseEvent(json: String): PlatformEvent = parseEvent(StrictJson.parseObject(json))

    fun parseApiError(json: String): ApiError {
        val root = StrictJson.parseObject(json, MAX_ERROR_DOCUMENT_CHARS)
            .shape(setOf("error"))
        val error = root.objectValue("error").shape(
            setOf("code", "message", "details", "request_id")
        )
        val details = error.array("details", maxItems = 100)
        return ApiError(
            code = requireErrorCode(error, "code"),
            message = error.string("message", minLength = 1, maxLength = 1_024),
            details = List(details.size) { index -> parseApiErrorDetail(details.objectAt(index)) },
            requestId = error.uuid("request_id")
        )
    }

    fun encodeDeviceEnrollmentRequest(request: DeviceEnrollmentRequest): String {
        PlatformValueRules.requireSha256(request.fingerprintHash, "fingerprint_hash")
        PlatformValueRules.requireSha256(request.installationKeyHash, "installation_key_hash")
        requireLength(request.manufacturer, 1, 128, "manufacturer")
        requireLength(request.model, 1, 128, "model")
        requireLength(request.appVersion, 1, 64, "app_version")
        return JSONObject()
            .put("fingerprint_hash", request.fingerprintHash)
            .put("installation_key_hash", request.installationKeyHash)
            .put("manufacturer", request.manufacturer)
            .put("model", request.model)
            .put("app_version", request.appVersion)
            .toString()
    }

    fun encodeBindingCreate(request: BindingCreateRequest): String {
        val productCode = PlatformValueRules.normalizeProductCode(request.productCode)
        request.productName?.let { requireLength(it, 0, 256, "product_name") }
        PlatformValueRules.requireTagId(request.tagId)
        PlatformValueRules.requireStationId(request.stationId)
        return JSONObject()
            .put("product_code", productCode)
            .putNullable("product_name", request.productName)
            .put("tag_id", request.tagId)
            .put("station_id", request.stationId)
            .toString()
    }

    fun encodeRebind(request: RebindRequest): String {
        val productCode = PlatformValueRules.normalizeProductCode(request.productCode)
        request.productName?.let { requireLength(it, 0, 256, "product_name") }
        PlatformValueRules.requireTagId(request.expectedTagId)
        return JSONObject()
            .put("product_code", productCode)
            .putNullable("product_name", request.productName)
            .put("expected_tag_id", request.expectedTagId)
            .toString()
    }

    fun encodeAndroidBindingMigrationPreview(
        request: AndroidBindingMigrationPreviewRequest
    ): String = JSONObject()
        .put("records", encodeAndroidBindingMigrationRecords(request.records))
        .toString()

    fun encodeAndroidBindingMigrationCommit(
        request: AndroidBindingMigrationCommitRequest
    ): String {
        requirePreviewToken(request.previewToken)
        require(request.selectedDuplicateKeys.size <= MAX_MIGRATION_RECORDS) {
            "selected_duplicate_keys exceeds the contract limit."
        }
        require(request.selectedDuplicateKeys.toSet().size == request.selectedDuplicateKeys.size) {
            "selected_duplicate_keys must be unique."
        }
        request.selectedDuplicateKeys.forEach(::requireClientRecordKey)
        val recordKeys = request.records.map { it.clientRecordKey }.toSet()
        require(request.selectedDuplicateKeys.all(recordKeys::contains)) {
            "selected_duplicate_keys must identify request records."
        }
        return JSONObject()
            .put("records", encodeAndroidBindingMigrationRecords(request.records))
            .put("preview_token", request.previewToken)
            .put("selected_duplicate_keys", JSONArray(request.selectedDuplicateKeys))
            .toString()
    }

    fun encodeTagRegistration(request: TagRegisterRequest): String {
        PlatformValueRules.requireTagId(request.tagId)
        PlatformValueRules.requireStationId(request.stationId)
        return JSONObject()
            .put("tag_id", request.tagId)
            .put("station_id", request.stationId)
            .toString()
    }

    fun encodeLightCommand(request: LightCommandRequest): String {
        val root = JSONObject().put("action", request.action.name)
        request.productCode?.let {
            root.put("product_code", PlatformValueRules.normalizeProductCode(it))
        }
        request.tagIds?.let { tagIds ->
            val array = JSONArray()
            tagIds.forEach { tagId ->
                PlatformValueRules.requireTagId(tagId)
                array.put(tagId)
            }
            root.put("tag_ids", array)
        }
        request.color?.let { root.put("color", it.name) }
        return root.toString()
    }

    private fun parseLiveness(root: StrictJsonObject): Liveness {
        root.shape(setOf("status", "service", "version", "checked_at"))
        return Liveness(
            status = root.string("status", constant = "alive"),
            service = root.string("service", constant = "HighTacPlatform"),
            version = root.string("version", minLength = 1, maxLength = 64),
            checkedAt = root.instant("checked_at")
        )
    }

    private fun parseReadiness(root: StrictJsonObject): Readiness {
        root.shape(setOf("status", "checks", "checked_at"))
        val checks = root.array("checks", minItems = 3, maxItems = 3)
        val parsedChecks = List(checks.size) { index -> parseReadinessCheck(checks.objectAt(index)) }
        if (parsedChecks.map { it.name }.toSet().size != parsedChecks.size) {
            throw PlatformJsonException("$.checks", "readiness check names must be unique")
        }
        return Readiness(
            status = root.enum("status"),
            checks = parsedChecks,
            checkedAt = root.instant("checked_at")
        )
    }

    private fun parseReadinessCheck(root: StrictJsonObject): ReadinessCheck {
        root.shape(setOf("name", "status", "message", "checked_at"))
        return ReadinessCheck(
            name = root.mappedString("name", ReadinessCheckName.entries) { it.wireName },
            status = root.enum("status"),
            message = root.string("message", maxLength = 512),
            checkedAt = root.instant("checked_at")
        )
    }

    private fun parseBrokerStatus(root: StrictJsonObject): BrokerStatus {
        root.shape(
            setOf(
                "service_name", "service_state", "endpoint", "tcp_reachable",
                "mqtt_connected", "subscriptions_ready", "started_at", "uptime_seconds",
                "checked_at"
            )
        )
        return BrokerStatus(
            serviceName = root.string("service_name", constant = "HighTacMqttBroker"),
            serviceState = root.enum("service_state"),
            endpoint = root.string("endpoint", pattern = BROKER_ENDPOINT_PATTERN),
            tcpReachable = root.boolean("tcp_reachable"),
            mqttConnected = root.boolean("mqtt_connected"),
            subscriptionsReady = root.boolean("subscriptions_ready"),
            startedAt = root.nullableInstant("started_at"),
            uptimeSeconds = root.nullableLong("uptime_seconds", minimum = 0),
            checkedAt = root.instant("checked_at")
        )
    }

    private fun parseDeviceEnrollmentCreated(root: StrictJsonObject): DeviceEnrollmentCreated {
        root.shape(
            required = setOf("id", "status", "expires_at"),
            optional = setOf(
                "poll_secret", "poll_after_seconds", "display_name", "device_id", "device_token"
            )
        )
        val status = root.enum<EnrollmentStatus>("status")
        val pollSecret = root.value.takeIf { it.has("poll_secret") }
            ?.let { root.nullableString("poll_secret", minLength = 32, maxLength = 256) }
            ?.let(SensitiveString::fromTransport)
        val pollAfterSeconds = root.value.takeIf { it.has("poll_after_seconds") }
            ?.let { root.nullableInt("poll_after_seconds", minimum = 1, maximum = 60) }
        val displayName = root.value.takeIf { it.has("display_name") }
            ?.let { root.nullableString("display_name", maxLength = 128) }
        val deviceId = root.value.takeIf { it.has("device_id") }
            ?.let { root.nullableUuid("device_id") }
        val token = root.value.takeIf { it.has("device_token") }
            ?.let { root.nullableString("device_token", minLength = 32, maxLength = 512) }
            ?.let(SensitiveString::fromTransport)
        if (status == EnrollmentStatus.PENDING && (pollSecret == null || pollAfterSeconds == null)) {
            throw PlatformJsonException(
                "$",
                "pending enrollment must include poll_secret and poll_after_seconds"
            )
        }
        if (status == EnrollmentStatus.APPROVED && deviceId == null) {
            throw PlatformJsonException(
                "$",
                "approved enrollment must include device_id"
            )
        }
        return DeviceEnrollmentCreated(
            id = root.uuid("id"),
            status = status,
            pollSecret = pollSecret,
            expiresAt = root.instant("expires_at"),
            pollAfterSeconds = pollAfterSeconds,
            displayName = displayName,
            deviceId = deviceId,
            deviceToken = token
        )
    }

    private fun parseDeviceEnrollmentState(root: StrictJsonObject): DeviceEnrollmentState {
        root.shape(
            setOf("id", "status", "display_name", "device_id", "device_token", "expires_at")
        )
        val token = root.nullableString("device_token", minLength = 32, maxLength = 512)
        return DeviceEnrollmentState(
            id = root.uuid("id"),
            status = root.enum("status"),
            displayName = root.nullableString("display_name", maxLength = 128),
            deviceId = root.nullableUuid("device_id"),
            deviceToken = token?.let(SensitiveString::fromTransport),
            expiresAt = root.instant("expires_at")
        )
    }

    private fun parseAndroidDevice(root: StrictJsonObject): AndroidDevice {
        root.shape(
            setOf(
                "id", "display_name", "manufacturer", "model", "app_version", "status",
                "online", "first_seen_at", "last_seen_at", "approved_at", "revoked_at"
            )
        )
        return AndroidDevice(
            id = root.uuid("id"),
            displayName = root.nullableString("display_name", maxLength = 128),
            manufacturer = root.string("manufacturer", minLength = 1, maxLength = 128),
            model = root.string("model", minLength = 1, maxLength = 128),
            appVersion = root.string("app_version", minLength = 1, maxLength = 64),
            status = root.enum("status"),
            online = root.boolean("online"),
            firstSeenAt = root.instant("first_seen_at"),
            lastSeenAt = root.nullableInstant("last_seen_at"),
            approvedAt = root.nullableInstant("approved_at"),
            revokedAt = root.nullableInstant("revoked_at")
        )
    }

    private fun parseProduct(root: StrictJsonObject): Product {
        root.shape(
            setOf(
                "id", "product_code", "product_name", "source", "is_active",
                "active_binding_count", "created_at", "updated_at"
            )
        )
        return Product(
            id = root.uuid("id"),
            productCode = checked("$.product_code") {
                PlatformValueRules.requireProductCode(
                    root.string("product_code", minLength = 1, maxLength = 128)
                )
            },
            productName = root.nullableString("product_name", maxLength = 256),
            source = root.enum("source"),
            isActive = root.boolean("is_active"),
            activeBindingCount = root.int("active_binding_count", minimum = 0),
            createdAt = root.instant("created_at"),
            updatedAt = root.instant("updated_at")
        )
    }

    private fun parseProductDetail(root: StrictJsonObject): ProductDetail {
        root.shape(setOf("product", "active_bindings"))
        val bindings = root.array("active_bindings")
        return ProductDetail(
            product = parseProduct(root.objectValue("product")),
            activeBindings = List(bindings.size) { parseBinding(bindings.objectAt(it)) }
        )
    }

    private fun parseBinding(root: StrictJsonObject): Binding {
        root.shape(
            setOf(
                "id", "product_id", "product_code", "product_name", "tag_id", "site_id",
                "station_id", "source", "actor_type", "actor_id", "actor_display_name",
                "bound_at", "unbound_at", "is_active"
            )
        )
        return Binding(
            id = root.uuid("id"),
            productId = root.uuid("product_id"),
            productCode = checked("$.product_code") {
                PlatformValueRules.requireProductCode(
                    root.string("product_code", minLength = 1, maxLength = 128)
                )
            },
            productName = root.nullableString("product_name", maxLength = 256),
            tagId = requireTagId(root, "tag_id"),
            siteId = root.uuid("site_id"),
            stationId = requireStationId(root, "station_id"),
            source = root.enum("source"),
            actorType = root.enum("actor_type"),
            actorId = root.string("actor_id", minLength = 1, maxLength = 128),
            actorDisplayName = root.string("actor_display_name", minLength = 1, maxLength = 128),
            boundAt = root.instant("bound_at"),
            unboundAt = root.nullableInstant("unbound_at"),
            isActive = root.boolean("is_active")
        )
    }

    private fun parseRebindResult(root: StrictJsonObject): RebindResult {
        root.shape(setOf("removed_binding", "created_binding"))
        return RebindResult(
            removedBinding = parseBinding(root.objectValue("removed_binding")),
            createdBinding = parseBinding(root.objectValue("created_binding"))
        )
    }

    private fun parseAndroidBindingMigrationPreview(
        root: StrictJsonObject
    ): AndroidBindingMigrationPreview {
        root.shape(setOf("preview_token", "summary", "records"))
        val recordsJson = root.array(
            "records",
            minItems = 1,
            maxItems = MAX_MIGRATION_RECORDS
        )
        val records = List(recordsJson.size) {
            parseAndroidBindingMigrationPreviewRecord(recordsJson.objectAt(it))
        }
        requireUniqueMigrationKeys(records.map { it.clientRecordKey })
        val summary = parseAndroidBindingMigrationPreviewSummary(root.objectValue("summary"))
        requirePreviewSummaryMatches(summary, records)
        return AndroidBindingMigrationPreview(
            previewToken = requirePreviewToken(
                root.string(
                    "preview_token",
                    minLength = 32,
                    maxLength = 2_048,
                    pattern = MIGRATION_PREVIEW_TOKEN_PATTERN
                )
            ),
            summary = summary,
            records = records
        )
    }

    private fun parseAndroidBindingMigrationPreviewRecord(
        root: StrictJsonObject
    ): AndroidBindingMigrationPreviewRecord {
        root.shape(
            setOf(
                "client_record_key", "product_code", "product_name", "tag_id",
                "station_id", "classification", "authoritative_binding_id",
                "authoritative_product_code", "authoritative_station_id"
            )
        )
        return AndroidBindingMigrationPreviewRecord(
            clientRecordKey = requireClientRecordKey(
                root.string("client_record_key", minLength = 1, maxLength = 256)
            ),
            productCode = checked("$.product_code") {
                PlatformValueRules.requireProductCode(
                    root.string("product_code", minLength = 1, maxLength = 128)
                )
            },
            productName = root.nullableString("product_name", maxLength = 256),
            tagId = requireTagId(root, "tag_id"),
            stationId = requireStationId(root, "station_id"),
            classification = root.enum("classification"),
            authoritativeBindingId = root.nullableUuid("authoritative_binding_id"),
            authoritativeProductCode = root.nullableString(
                "authoritative_product_code",
                minLength = 1,
                maxLength = 128
            )?.let { checked("$.authoritative_product_code") {
                PlatformValueRules.requireProductCode(it)
            } },
            authoritativeStationId = nullableStationId(root, "authoritative_station_id")
        )
    }

    private fun parseAndroidBindingMigrationPreviewSummary(
        root: StrictJsonObject
    ): AndroidBindingMigrationPreviewSummary {
        root.shape(
            setOf(
                "total_records", "migratable", "identical", "duplicate_legacy_tag",
                "tag_bound_to_different_product", "station_mismatch", "station_not_found"
            )
        )
        return AndroidBindingMigrationPreviewSummary(
            totalRecords = root.int("total_records", minimum = 1, maximum = MAX_MIGRATION_RECORDS),
            migratable = root.int("migratable", minimum = 0, maximum = MAX_MIGRATION_RECORDS),
            identical = root.int("identical", minimum = 0, maximum = MAX_MIGRATION_RECORDS),
            duplicateLegacyTag = root.int(
                "duplicate_legacy_tag",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            tagBoundToDifferentProduct = root.int(
                "tag_bound_to_different_product",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            stationMismatch = root.int(
                "station_mismatch",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            stationNotFound = root.int(
                "station_not_found",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            )
        )
    }

    private fun parseAndroidBindingMigrationCommit(
        root: StrictJsonObject
    ): AndroidBindingMigrationCommitResult {
        root.shape(setOf("committed_at", "summary", "records", "bindings"))
        val recordsJson = root.array(
            "records",
            minItems = 1,
            maxItems = MAX_MIGRATION_RECORDS
        )
        val records = List(recordsJson.size) {
            parseAndroidBindingMigrationCommitRecord(recordsJson.objectAt(it))
        }
        requireUniqueMigrationKeys(records.map { it.clientRecordKey })
        val bindingsJson = root.array("bindings", maxItems = MAX_MIGRATION_RECORDS)
        val bindings = List(bindingsJson.size) { parseBinding(bindingsJson.objectAt(it)) }
        val summary = parseAndroidBindingMigrationCommitSummary(root.objectValue("summary"))
        requireCommitSummaryMatches(summary, records, bindings)
        return AndroidBindingMigrationCommitResult(
            committedAt = root.instant("committed_at"),
            summary = summary,
            records = records,
            bindings = bindings
        )
    }

    private fun parseAndroidBindingMigrationCommitRecord(
        root: StrictJsonObject
    ): AndroidBindingMigrationCommitRecord {
        root.shape(setOf("client_record_key", "classification", "outcome", "binding_id"))
        val record = AndroidBindingMigrationCommitRecord(
            clientRecordKey = requireClientRecordKey(
                root.string("client_record_key", minLength = 1, maxLength = 256)
            ),
            classification = root.enum("classification"),
            outcome = root.enum("outcome"),
            bindingId = root.nullableUuid("binding_id")
        )
        val validOutcome = when (record.classification) {
            AndroidBindingMigrationClassification.MIGRATABLE ->
                record.outcome == AndroidBindingMigrationOutcome.MIGRATED
            AndroidBindingMigrationClassification.IDENTICAL ->
                record.outcome == AndroidBindingMigrationOutcome.IDENTICAL
            AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG ->
                record.outcome == AndroidBindingMigrationOutcome.MIGRATED ||
                    record.outcome == AndroidBindingMigrationOutcome.SKIPPED
            AndroidBindingMigrationClassification.TAG_BOUND_TO_DIFFERENT_PRODUCT,
            AndroidBindingMigrationClassification.STATION_MISMATCH,
            AndroidBindingMigrationClassification.STATION_NOT_FOUND ->
                record.outcome == AndroidBindingMigrationOutcome.SKIPPED
        }
        if (!validOutcome || (record.outcome == AndroidBindingMigrationOutcome.SKIPPED) !=
            (record.bindingId == null)
        ) {
            throw PlatformJsonException("$.records", "migration outcome is inconsistent")
        }
        return record
    }

    private fun parseAndroidBindingMigrationCommitSummary(
        root: StrictJsonObject
    ): AndroidBindingMigrationCommitSummary {
        root.shape(
            setOf(
                "total_records", "migrated_records", "identical_records", "skipped_records",
                "created_products", "created_tags", "created_bindings"
            )
        )
        return AndroidBindingMigrationCommitSummary(
            totalRecords = root.int("total_records", minimum = 1, maximum = MAX_MIGRATION_RECORDS),
            migratedRecords = root.int(
                "migrated_records",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            identicalRecords = root.int(
                "identical_records",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            skippedRecords = root.int(
                "skipped_records",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            createdProducts = root.int(
                "created_products",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            createdTags = root.int(
                "created_tags",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            ),
            createdBindings = root.int(
                "created_bindings",
                minimum = 0,
                maximum = MAX_MIGRATION_RECORDS
            )
        )
    }

    private fun parseStation(root: StrictJsonObject): Station {
        root.shape(
            setOf(
                "station_id", "site_id", "alias", "status", "mac", "firmware_version",
                "server_address", "heartbeat_seconds", "last_heartbeat_at", "total_count",
                "send_count", "broker_connected", "created_at", "updated_at"
            )
        )
        return Station(
            stationId = requireStationId(root, "station_id"),
            siteId = root.uuid("site_id"),
            alias = root.nullableString("alias", maxLength = 128),
            status = root.enum("status"),
            mac = root.nullableString("mac", maxLength = 64),
            firmwareVersion = root.nullableString("firmware_version", maxLength = 64),
            serverAddress = root.nullableString("server_address", maxLength = 255),
            heartbeatSeconds = root.nullableInt("heartbeat_seconds", minimum = 1, maximum = 3_600),
            lastHeartbeatAt = root.nullableInstant("last_heartbeat_at"),
            totalCount = root.int("total_count", minimum = 0),
            sendCount = root.int("send_count", minimum = 0),
            brokerConnected = root.boolean("broker_connected"),
            createdAt = root.instant("created_at"),
            updatedAt = root.instant("updated_at")
        )
    }

    private fun parseTag(root: StrictJsonObject): Tag {
        root.shape(
            setOf(
                "tag_id", "site_id", "station_id", "registered_at", "first_seen_at",
                "last_seen_at", "online", "battery_raw", "battery_voltage", "battery_level",
                "low_battery", "firmware_version", "group_no", "last_result_type",
                "is_abnormal", "abnormal_reason", "active_binding_id"
            )
        )
        return Tag(
            tagId = requireTagId(root, "tag_id"),
            siteId = root.nullableUuid("site_id"),
            stationId = nullableStationId(root, "station_id"),
            registeredAt = root.nullableInstant("registered_at"),
            firstSeenAt = root.nullableInstant("first_seen_at"),
            lastSeenAt = root.nullableInstant("last_seen_at"),
            online = root.boolean("online"),
            batteryRaw = root.nullableInt("battery_raw", minimum = 0),
            batteryVoltage = root.nullableDouble("battery_voltage", minimum = 0.0),
            batteryLevel = requireBatteryLevel(root, "battery_level"),
            lowBattery = root.boolean("low_battery"),
            firmwareVersion = root.nullableString("firmware_version", maxLength = 64),
            groupNo = root.nullableInt("group_no", minimum = 0, maximum = 255),
            lastResultType = root.nullableInt("last_result_type", minimum = 0, maximum = 255),
            isAbnormal = root.boolean("is_abnormal"),
            abnormalReason = root.nullableString("abnormal_reason", maxLength = 512),
            activeBindingId = root.nullableUuid("active_binding_id")
        )
    }

    private fun parseTagDetail(root: StrictJsonObject): TagDetail {
        root.shape(setOf("tag", "active_binding", "recent_history"))
        val history = root.array("recent_history", maxItems = 100)
        return TagDetail(
            tag = parseTag(root.objectValue("tag")),
            activeBinding = root.nullableObject("active_binding")?.let(::parseBinding),
            recentHistory = List(history.size) { parseTagHistory(history.objectAt(it)) }
        )
    }

    private fun parseTagHistory(root: StrictJsonObject): TagStatusHistoryItem {
        root.shape(
            setOf(
                "id", "occurred_at", "online", "battery_raw", "battery_level", "result_type",
                "is_abnormal", "abnormal_reason"
            )
        )
        return TagStatusHistoryItem(
            id = root.uuid("id"),
            occurredAt = root.instant("occurred_at"),
            online = root.boolean("online"),
            batteryRaw = root.nullableInt("battery_raw", minimum = 0),
            batteryLevel = requireBatteryLevel(root, "battery_level"),
            resultType = root.nullableInt("result_type", minimum = 0, maximum = 255),
            isAbnormal = root.boolean("is_abnormal"),
            abnormalReason = root.nullableString("abnormal_reason", maxLength = 512)
        )
    }

    private fun parseLightCommand(root: StrictJsonObject): LightCommand {
        root.shape(
            setOf(
                "id", "action", "product_id", "product_code", "requested_color", "status",
                "target_count", "confirmed_count", "unconfirmed_count", "failed_count",
                "created_at", "published_at", "completed_at", "items"
            )
        )
        val items = root.array("items", maxItems = 2_000)
        val productCode = root.nullableString("product_code", minLength = 1, maxLength = 128)
            ?.let { checked("$.product_code") { PlatformValueRules.requireProductCode(it) } }
        return LightCommand(
            id = root.uuid("id"),
            action = root.enum("action"),
            productId = root.nullableUuid("product_id"),
            productCode = productCode,
            requestedColor = nullableEnum(root, "requested_color", LightColor.entries),
            status = root.enum("status"),
            targetCount = root.int("target_count", minimum = 0),
            confirmedCount = root.int("confirmed_count", minimum = 0),
            unconfirmedCount = root.int("unconfirmed_count", minimum = 0),
            failedCount = root.int("failed_count", minimum = 0),
            createdAt = root.instant("created_at"),
            publishedAt = root.nullableInstant("published_at"),
            completedAt = root.nullableInstant("completed_at"),
            items = List(items.size) { parseCommandItem(items.objectAt(it)) }
        )
    }

    private fun parseCommandItem(root: StrictJsonObject): CommandItem {
        root.shape(
            setOf(
                "id", "tag_id", "station_id", "status", "publish_attempts", "published_at",
                "confirmed_at", "last_result_type", "correlation", "failure_code"
            )
        )
        return CommandItem(
            id = root.uuid("id"),
            tagId = requireTagId(root, "tag_id"),
            stationId = requireStationId(root, "station_id"),
            status = root.enum("status"),
            publishAttempts = root.int("publish_attempts", minimum = 0, maximum = 2),
            publishedAt = root.nullableInstant("published_at"),
            confirmedAt = root.nullableInstant("confirmed_at"),
            lastResultType = root.nullableInt("last_result_type", minimum = 0, maximum = 255),
            correlation = root.enum("correlation"),
            failureCode = root.nullableString("failure_code", maxLength = 64)
        )
    }

    private fun parseEvent(root: StrictJsonObject): PlatformEvent {
        root.shape(
            setOf(
                "event_id", "schema_version", "event_type", "occurred_at", "entity_type",
                "entity_id", "payload"
            )
        )
        val base = EventBase(
            eventId = root.uuid("event_id"),
            schemaVersion = root.int("schema_version", minimum = 1, maximum = 1),
            eventType = root.string("event_type", minLength = 1, maxLength = 128),
            occurredAt = root.instant("occurred_at"),
            entityType = root.mappedString("entity_type", EventEntityType.entries) { it.wireName },
            entityId = root.string("entity_id", minLength = 1, maxLength = 128)
        )
        val payload = root.objectValue("payload")
        return when (base.eventType) {
            "broker.status_changed" -> parseBrokerEvent(base, payload)
            "station.status_changed" -> parseStationStatusEvent(base, payload)
            "station.heartbeat" -> parseStationHeartbeatEvent(base, payload)
            "tag.status_changed" -> parseTagStatusEvent(base, payload)
            "binding.created" -> parseBindingCreatedEvent(base, payload)
            "binding.removed" -> parseBindingRemovedEvent(base, payload)
            "command.status_changed" -> parseCommandStatusEvent(base, payload)
            "device.status_changed" -> parseDeviceStatusEvent(base, payload)
            "system.notice" -> parseSystemNoticeEvent(base, payload)
            else -> throw PlatformJsonException("$.event_type", "unknown event type")
        }
    }

    private fun parseBrokerEvent(base: EventBase, root: StrictJsonObject): BrokerStatusChangedEvent {
        requireEventIdentity(base, EventEntityType.BROKER, "HighTacMqttBroker")
        root.shape(
            setOf(
                "previous_status", "current_status", "windows_service_running", "tcp_reachable",
                "mqtt_connected", "subscriptions_ready", "endpoint", "reason"
            )
        )
        return BrokerStatusChangedEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = BrokerStatusChangedPayload(
                previousStatus = root.enum("previous_status"),
                currentStatus = root.enum("current_status"),
                windowsServiceRunning = root.boolean("windows_service_running"),
                tcpReachable = root.boolean("tcp_reachable"),
                mqttConnected = root.boolean("mqtt_connected"),
                subscriptionsReady = root.boolean("subscriptions_ready"),
                endpoint = root.string("endpoint", pattern = BROKER_ENDPOINT_PATTERN),
                reason = root.nullableString("reason", maxLength = 512)
            )
        )
    }

    private fun parseStationStatusEvent(
        base: EventBase,
        root: StrictJsonObject
    ): StationStatusChangedEvent {
        requireEventIdentity(base, EventEntityType.STATION)
        root.shape(
            setOf(
                "station_id", "previous_status", "current_status", "last_heartbeat_at",
                "broker_connected", "reason"
            )
        )
        val stationId = requireStationId(root, "station_id")
        requireEntityPayloadMatch(base, stationId)
        return StationStatusChangedEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = StationStatusChangedPayload(
                stationId = stationId,
                previousStatus = root.enum("previous_status"),
                currentStatus = root.enum("current_status"),
                lastHeartbeatAt = root.nullableInstant("last_heartbeat_at"),
                brokerConnected = root.boolean("broker_connected"),
                reason = root.nullableString("reason", maxLength = 512)
            )
        )
    }

    private fun parseStationHeartbeatEvent(
        base: EventBase,
        root: StrictJsonObject
    ): StationHeartbeatEvent {
        requireEventIdentity(base, EventEntityType.STATION)
        root.shape(
            setOf(
                "station_id", "status", "mac", "alias", "server_address", "heartbeat_seconds",
                "firmware_version", "total_count", "send_count"
            )
        )
        val stationId = requireStationId(root, "station_id")
        requireEntityPayloadMatch(base, stationId)
        return StationHeartbeatEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = StationHeartbeatPayload(
                stationId = stationId,
                status = root.enum("status"),
                mac = root.string("mac", maxLength = 64),
                alias = root.nullableString("alias", maxLength = 128),
                serverAddress = root.string("server_address", maxLength = 255),
                heartbeatSeconds = root.int("heartbeat_seconds", minimum = 1, maximum = 3_600),
                firmwareVersion = root.string("firmware_version", maxLength = 64),
                totalCount = root.int("total_count", minimum = 0),
                sendCount = root.int("send_count", minimum = 0)
            )
        )
    }

    private fun parseTagStatusEvent(base: EventBase, root: StrictJsonObject): TagStatusChangedEvent {
        requireEventIdentity(base, EventEntityType.TAG)
        root.shape(
            setOf(
                "tag_id", "station_id", "online", "last_seen_at", "battery_raw",
                "battery_voltage", "battery_level", "low_battery", "is_abnormal",
                "abnormal_reason", "last_result_type"
            )
        )
        val tagId = requireTagId(root, "tag_id")
        requireEntityPayloadMatch(base, tagId)
        return TagStatusChangedEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = TagStatusChangedPayload(
                tagId = tagId,
                stationId = nullableStationId(root, "station_id"),
                online = root.boolean("online"),
                lastSeenAt = root.nullableInstant("last_seen_at"),
                batteryRaw = root.nullableInt("battery_raw", minimum = 0),
                batteryVoltage = root.nullableDouble("battery_voltage", minimum = 0.0),
                batteryLevel = requireBatteryLevel(root, "battery_level"),
                lowBattery = root.boolean("low_battery"),
                isAbnormal = root.boolean("is_abnormal"),
                abnormalReason = root.nullableString("abnormal_reason", maxLength = 512),
                lastResultType = root.nullableInt("last_result_type", minimum = 0, maximum = 255)
            )
        )
    }

    private fun parseBindingCreatedEvent(
        base: EventBase,
        root: StrictJsonObject
    ): BindingCreatedEvent {
        requireEventIdentity(base, EventEntityType.BINDING)
        root.shape(setOf("binding", "bound_at"))
        val binding = parseBindingSnapshot(root.objectValue("binding"))
        requireEntityPayloadMatch(base, binding.bindingId.toString())
        return BindingCreatedEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = BindingCreatedPayload(binding, root.instant("bound_at"))
        )
    }

    private fun parseBindingRemovedEvent(
        base: EventBase,
        root: StrictJsonObject
    ): BindingRemovedEvent {
        requireEventIdentity(base, EventEntityType.BINDING)
        root.shape(setOf("binding", "unbound_at", "replacement_binding_id"))
        val binding = parseBindingSnapshot(root.objectValue("binding"))
        requireEntityPayloadMatch(base, binding.bindingId.toString())
        return BindingRemovedEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = BindingRemovedPayload(
                binding = binding,
                unboundAt = root.instant("unbound_at"),
                replacementBindingId = root.nullableUuid("replacement_binding_id")
            )
        )
    }

    private fun parseBindingSnapshot(root: StrictJsonObject): BindingSnapshot {
        root.shape(
            setOf(
                "binding_id", "product_id", "product_code", "product_name", "tag_id", "site_id",
                "station_id", "source", "actor_type", "actor_id"
            )
        )
        return BindingSnapshot(
            bindingId = root.uuid("binding_id"),
            productId = root.uuid("product_id"),
            productCode = checked("$.payload.binding.product_code") {
                PlatformValueRules.requireProductCode(
                    root.string("product_code", minLength = 1, maxLength = 128)
                )
            },
            productName = root.nullableString("product_name", maxLength = 256),
            tagId = requireTagId(root, "tag_id"),
            siteId = root.uuid("site_id"),
            stationId = requireStationId(root, "station_id"),
            source = root.enum("source"),
            actorType = root.enum("actor_type"),
            actorId = root.string("actor_id", minLength = 1, maxLength = 128)
        )
    }

    private fun parseCommandStatusEvent(
        base: EventBase,
        root: StrictJsonObject
    ): CommandStatusChangedEvent {
        requireEventIdentity(base, EventEntityType.COMMAND)
        root.shape(
            setOf(
                "command_id", "action", "previous_status", "current_status", "target_count",
                "confirmed_count", "unconfirmed_count", "failed_count", "item"
            )
        )
        val commandId = root.uuid("command_id")
        requireEntityPayloadMatch(base, commandId.toString())
        return CommandStatusChangedEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = CommandStatusChangedPayload(
                commandId = commandId,
                action = root.enum("action"),
                previousStatus = root.enum("previous_status"),
                currentStatus = root.enum("current_status"),
                targetCount = root.int("target_count", minimum = 0),
                confirmedCount = root.int("confirmed_count", minimum = 0),
                unconfirmedCount = root.int("unconfirmed_count", minimum = 0),
                failedCount = root.int("failed_count", minimum = 0),
                item = root.nullableObject("item")?.let(::parseCommandItemUpdate)
            )
        )
    }

    private fun parseCommandItemUpdate(root: StrictJsonObject): CommandItemUpdate {
        root.shape(
            setOf(
                "item_id", "tag_id", "station_id", "previous_status", "current_status",
                "correlation", "result_type"
            )
        )
        return CommandItemUpdate(
            itemId = root.uuid("item_id"),
            tagId = requireTagId(root, "tag_id"),
            stationId = requireStationId(root, "station_id"),
            previousStatus = root.enum("previous_status"),
            currentStatus = root.enum("current_status"),
            correlation = root.enum("correlation"),
            resultType = root.nullableInt("result_type", minimum = 0, maximum = 255)
        )
    }

    private fun parseDeviceStatusEvent(
        base: EventBase,
        root: StrictJsonObject
    ): DeviceStatusChangedEvent {
        requireEventIdentity(base, EventEntityType.DEVICE)
        root.shape(
            setOf(
                "device_id", "display_name", "previous_status", "current_status", "manufacturer",
                "model", "app_version", "reason"
            )
        )
        val deviceId = root.uuid("device_id")
        requireEntityPayloadMatch(base, deviceId.toString())
        return DeviceStatusChangedEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = DeviceStatusChangedPayload(
                deviceId = deviceId,
                displayName = root.nullableString("display_name", maxLength = 128),
                previousStatus = root.enum("previous_status"),
                currentStatus = root.enum("current_status"),
                manufacturer = root.string("manufacturer", minLength = 1, maxLength = 128),
                model = root.string("model", minLength = 1, maxLength = 128),
                appVersion = root.string("app_version", minLength = 1, maxLength = 64),
                reason = root.nullableString("reason", maxLength = 512)
            )
        )
    }

    private fun parseSystemNoticeEvent(base: EventBase, root: StrictJsonObject): SystemNoticeEvent {
        requireEventIdentity(base, EventEntityType.SYSTEM, "HighTacPlatform")
        root.shape(setOf("severity", "code", "message", "resource_type", "resource_id"))
        return SystemNoticeEvent(
            eventId = base.eventId,
            schemaVersion = base.schemaVersion,
            occurredAt = base.occurredAt,
            entityType = base.entityType,
            entityId = base.entityId,
            payload = SystemNoticePayload(
                severity = root.enum("severity"),
                code = requireErrorCode(root, "code"),
                message = root.string("message", minLength = 1, maxLength = 1_024),
                resourceType = root.nullableString("resource_type", maxLength = 64),
                resourceId = root.nullableString("resource_id", maxLength = 128)
            )
        )
    }

    private fun parseApiErrorDetail(root: StrictJsonObject): ApiErrorDetail {
        root.shape(setOf("field", "code", "message"))
        return ApiErrorDetail(
            field = root.nullableString("field", maxLength = 256),
            code = requireErrorCode(root, "code"),
            message = root.string("message", minLength = 1, maxLength = 1_024)
        )
    }

    private fun <T> parsePage(
        root: StrictJsonObject,
        itemParser: (StrictJsonObject) -> T
    ): Page<T> {
        root.shape(setOf("items", "pagination"))
        val items = root.array("items")
        return Page(
            items = List(items.size) { itemParser(items.objectAt(it)) },
            pagination = parsePagination(root.objectValue("pagination"))
        )
    }

    private fun parsePagination(root: StrictJsonObject): Pagination {
        root.shape(setOf("page", "page_size", "total_items", "total_pages"))
        val pageSize = root.int("page_size")
        if (pageSize !in setOf(20, 50, 100)) {
            throw PlatformJsonException("$.pagination.page_size", "unsupported page size")
        }
        return Pagination(
            page = root.int("page", minimum = 1),
            pageSize = pageSize,
            totalItems = root.long("total_items", minimum = 0),
            totalPages = root.int("total_pages", minimum = 0)
        )
    }

    private fun encodeAndroidBindingMigrationRecords(
        records: List<AndroidBindingMigrationRecord>
    ): JSONArray {
        require(records.size in 1..MAX_MIGRATION_RECORDS) {
            "records length is outside the contract range."
        }
        val keys = records.map { requireClientRecordKey(it.clientRecordKey) }
        require(keys.toSet().size == keys.size) { "client_record_key values must be unique." }
        return JSONArray().also { array ->
            records.forEach { record ->
                val productCode = PlatformValueRules.normalizeProductCode(record.productCode)
                record.productName?.let { requireLength(it, 0, 256, "product_name") }
                val rawTagId = record.tagId.uppercase()
                require(MIGRATION_TAG_INPUT_PATTERN.matches(rawTagId)) {
                    "tag_id has an invalid migration format."
                }
                val tagId = if (rawTagId.length == 9) "AD1$rawTagId" else rawTagId
                PlatformValueRules.requireTagId(tagId)
                val stationId = record.stationId.uppercase()
                require(MIGRATION_STATION_INPUT_PATTERN.matches(stationId)) {
                    "station_id has an invalid migration format."
                }
                PlatformValueRules.requireStationId(stationId)
                array.put(
                    JSONObject()
                        .put("client_record_key", record.clientRecordKey)
                        .put("product_code", productCode)
                        .putNullable("product_name", record.productName)
                        .put("tag_id", tagId)
                        .put("station_id", stationId)
                )
            }
        }
    }

    private fun requireClientRecordKey(value: String): String {
        require(value.length in 1..256 && MIGRATION_CLIENT_RECORD_KEY_PATTERN.matches(value)) {
            "client_record_key has an invalid format."
        }
        return value
    }

    private fun requirePreviewToken(value: String): String {
        require(value.length in 32..2_048 && MIGRATION_PREVIEW_TOKEN_PATTERN.matches(value)) {
            "preview_token has an invalid format."
        }
        return value
    }

    private fun requireUniqueMigrationKeys(keys: List<String>) {
        if (keys.toSet().size != keys.size) {
            throw PlatformJsonException("$.records", "client_record_key values must be unique")
        }
    }

    private fun requirePreviewSummaryMatches(
        summary: AndroidBindingMigrationPreviewSummary,
        records: List<AndroidBindingMigrationPreviewRecord>
    ) {
        val expected = mapOf(
            AndroidBindingMigrationClassification.MIGRATABLE to summary.migratable,
            AndroidBindingMigrationClassification.IDENTICAL to summary.identical,
            AndroidBindingMigrationClassification.DUPLICATE_LEGACY_TAG to
                summary.duplicateLegacyTag,
            AndroidBindingMigrationClassification.TAG_BOUND_TO_DIFFERENT_PRODUCT to
                summary.tagBoundToDifferentProduct,
            AndroidBindingMigrationClassification.STATION_MISMATCH to summary.stationMismatch,
            AndroidBindingMigrationClassification.STATION_NOT_FOUND to summary.stationNotFound
        )
        if (summary.totalRecords != records.size || expected.any { (classification, count) ->
                records.count { it.classification == classification } != count
            }
        ) {
            throw PlatformJsonException("$.summary", "migration preview counts do not match records")
        }
    }

    private fun requireCommitSummaryMatches(
        summary: AndroidBindingMigrationCommitSummary,
        records: List<AndroidBindingMigrationCommitRecord>,
        bindings: List<Binding>
    ) {
        val migratedIds = records
            .filter { it.outcome == AndroidBindingMigrationOutcome.MIGRATED }
            .mapNotNull { it.bindingId }
        val bindingIds = bindings.map { it.id }
        val countsMatch = summary.totalRecords == records.size &&
            summary.migratedRecords == records.count {
                it.outcome == AndroidBindingMigrationOutcome.MIGRATED
            } &&
            summary.identicalRecords == records.count {
                it.outcome == AndroidBindingMigrationOutcome.IDENTICAL
            } &&
            summary.skippedRecords == records.count {
                it.outcome == AndroidBindingMigrationOutcome.SKIPPED
            } &&
            summary.createdBindings == bindings.size &&
            summary.createdBindings == summary.migratedRecords &&
            summary.createdProducts <= summary.createdBindings &&
            summary.createdTags <= summary.createdBindings
        val bindingsMatch = migratedIds.toSet() == bindingIds.toSet() &&
            migratedIds.size == migratedIds.toSet().size &&
            bindingIds.size == bindingIds.toSet().size &&
            bindings.all { it.source == BindingSource.MIGRATION && it.isActive && it.unboundAt == null }
        if (!countsMatch || !bindingsMatch) {
            throw PlatformJsonException("$.summary", "migration commit summary does not match records")
        }
    }

    private fun requireTagId(root: StrictJsonObject, name: String): String {
        val value = root.string(name)
        return checked("$.$name") { PlatformValueRules.requireTagId(value) }
    }

    private fun requireStationId(root: StrictJsonObject, name: String): String {
        val value = root.string(name)
        return checked("$.$name") { PlatformValueRules.requireStationId(value) }
    }

    private fun nullableStationId(root: StrictJsonObject, name: String): String? {
        val value = root.nullableString(name) ?: return null
        return checked("$.$name") { PlatformValueRules.requireStationId(value) }
    }

    private fun requireBatteryLevel(root: StrictJsonObject, name: String): Int? {
        val value = root.nullableInt(name, minimum = 0, maximum = 100)
        return checked("$.$name") { PlatformValueRules.requireBatteryLevel(value) }
    }

    private fun requireErrorCode(root: StrictJsonObject, name: String): String {
        val value = root.string(name)
        return checked("$.$name") { PlatformValueRules.requireErrorCode(value) }
    }

    private fun <T : Enum<T>> nullableEnum(
        root: StrictJsonObject,
        name: String,
        values: Iterable<T>
    ): T? {
        val value = root.nullableString(name) ?: return null
        return values.firstOrNull { it.name == value }
            ?: throw PlatformJsonException("$.$name", "unknown enum value")
    }

    private fun requireEventIdentity(
        base: EventBase,
        expectedType: EventEntityType,
        expectedId: String? = null
    ) {
        if (base.entityType != expectedType) {
            throw PlatformJsonException("$.entity_type", "entity type does not match event type")
        }
        expectedId?.let {
            if (base.entityId != it) {
                throw PlatformJsonException("$.entity_id", "entity ID does not match event type")
            }
        }
    }

    private fun requireEntityPayloadMatch(base: EventBase, payloadId: String) {
        if (base.entityId != payloadId) {
            throw PlatformJsonException("$.entity_id", "entity ID does not match payload")
        }
    }

    private inline fun <T> checked(path: String, block: () -> T): T {
        return try {
            block()
        } catch (error: IllegalArgumentException) {
            throw PlatformJsonException(path, error.message ?: "value is invalid", error)
        }
    }

    private fun requireLength(value: String, minimum: Int, maximum: Int, name: String) {
        require(value.length in minimum..maximum) { "$name length is outside the contract range." }
    }

    private data class EventBase(
        val eventId: UUID,
        val schemaVersion: Int,
        val eventType: String,
        val occurredAt: java.time.Instant,
        val entityType: EventEntityType,
        val entityId: String
    )

    private const val MAX_ERROR_DOCUMENT_CHARS = 64 * 1024
    private const val MAX_MIGRATION_RECORDS = 2_000
    private val BROKER_ENDPOINT_PATTERN = Regex("^[^\\s:]+:[0-9]{1,5}$")
    private val MIGRATION_CLIENT_RECORD_KEY_PATTERN = Regex("^\\S(?:.*\\S)?$")
    private val MIGRATION_PREVIEW_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    private val MIGRATION_TAG_INPUT_PATTERN = Regex("^(?:AD1)?[0-9A-F]{9}$")
    private val MIGRATION_STATION_INPUT_PATTERN = Regex("^90A9F[0-9A-F]{7}$")
}
