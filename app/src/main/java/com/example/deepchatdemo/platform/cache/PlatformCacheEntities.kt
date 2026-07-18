package com.example.deepchatdemo.platform.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.deepchatdemo.platform.model.ActorType
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingSource
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.ProductSource
import com.example.deepchatdemo.platform.model.Tag
import java.time.Instant
import java.util.UUID

@Entity(
    tableName = "cached_products",
    primaryKeys = ["endpoint", "id"],
    indices = [Index(value = ["endpoint", "product_code"], unique = true)]
)
data class CachedProductEntity(
    val endpoint: String,
    val id: String,
    @ColumnInfo(name = "product_code") val productCode: String,
    @ColumnInfo(name = "product_name") val productName: String?,
    val source: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "active_binding_count") val activeBindingCount: Int,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "cached_active_bindings",
    primaryKeys = ["endpoint", "id"],
    indices = [Index(value = ["endpoint", "tag_id"], unique = true)]
)
data class CachedBindingEntity(
    val endpoint: String,
    val id: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "product_code") val productCode: String,
    @ColumnInfo(name = "product_name") val productName: String?,
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "station_id") val stationId: String,
    val source: String,
    @ColumnInfo(name = "actor_type") val actorType: String,
    @ColumnInfo(name = "actor_id") val actorId: String,
    @ColumnInfo(name = "actor_display_name") val actorDisplayName: String,
    @ColumnInfo(name = "bound_at_epoch_millis") val boundAtEpochMillis: Long
)

@Entity(tableName = "cached_tags", primaryKeys = ["endpoint", "tag_id"])
data class CachedTagEntity(
    val endpoint: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "site_id") val siteId: String?,
    @ColumnInfo(name = "station_id") val stationId: String?,
    @ColumnInfo(name = "registered_at_epoch_millis") val registeredAtEpochMillis: Long?,
    @ColumnInfo(name = "first_seen_at_epoch_millis") val firstSeenAtEpochMillis: Long?,
    @ColumnInfo(name = "last_seen_at_epoch_millis") val lastSeenAtEpochMillis: Long?,
    val online: Boolean,
    @ColumnInfo(name = "battery_raw") val batteryRaw: Int?,
    @ColumnInfo(name = "battery_voltage") val batteryVoltage: Double?,
    @ColumnInfo(name = "battery_level") val batteryLevel: Int?,
    @ColumnInfo(name = "low_battery") val lowBattery: Boolean,
    @ColumnInfo(name = "firmware_version") val firmwareVersion: String?,
    @ColumnInfo(name = "group_no") val groupNo: Int?,
    @ColumnInfo(name = "last_result_type") val lastResultType: Int?,
    @ColumnInfo(name = "is_abnormal") val isAbnormal: Boolean,
    @ColumnInfo(name = "abnormal_reason") val abnormalReason: String?,
    @ColumnInfo(name = "active_binding_id") val activeBindingId: String?
)

@Entity(tableName = "cache_metadata")
data class CacheMetadataEntity(
    @PrimaryKey val endpoint: String,
    @ColumnInfo(name = "products_synchronized_at_epoch_millis")
    val productsSynchronizedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "bindings_synchronized_at_epoch_millis")
    val bindingsSynchronizedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "tags_synchronized_at_epoch_millis")
    val tagsSynchronizedAtEpochMillis: Long? = null
)

internal fun Product.toCacheEntity(endpoint: String) = CachedProductEntity(
    endpoint = endpoint,
    id = id.toString(),
    productCode = productCode,
    productName = productName,
    source = source.name,
    isActive = isActive,
    activeBindingCount = activeBindingCount,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli()
)

internal fun CachedProductEntity.toModel() = Product(
    id = UUID.fromString(id),
    productCode = productCode,
    productName = productName,
    source = ProductSource.valueOf(source),
    isActive = isActive,
    activeBindingCount = activeBindingCount,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis)
)

internal fun Binding.toCacheEntity(endpoint: String) = CachedBindingEntity(
    endpoint = endpoint,
    id = id.toString(),
    productId = productId.toString(),
    productCode = productCode,
    productName = productName,
    tagId = tagId,
    siteId = siteId.toString(),
    stationId = stationId,
    source = source.name,
    actorType = actorType.name,
    actorId = actorId,
    actorDisplayName = actorDisplayName,
    boundAtEpochMillis = boundAt.toEpochMilli()
)

internal fun CachedBindingEntity.toModel() = Binding(
    id = UUID.fromString(id),
    productId = UUID.fromString(productId),
    productCode = productCode,
    productName = productName,
    tagId = tagId,
    siteId = UUID.fromString(siteId),
    stationId = stationId,
    source = BindingSource.valueOf(source),
    actorType = ActorType.valueOf(actorType),
    actorId = actorId,
    actorDisplayName = actorDisplayName,
    boundAt = Instant.ofEpochMilli(boundAtEpochMillis),
    unboundAt = null,
    isActive = true
)

internal fun Tag.toCacheEntity(endpoint: String) = CachedTagEntity(
    endpoint = endpoint,
    tagId = tagId,
    siteId = siteId?.toString(),
    stationId = stationId,
    registeredAtEpochMillis = registeredAt?.toEpochMilli(),
    firstSeenAtEpochMillis = firstSeenAt?.toEpochMilli(),
    lastSeenAtEpochMillis = lastSeenAt?.toEpochMilli(),
    online = online,
    batteryRaw = batteryRaw,
    batteryVoltage = batteryVoltage,
    batteryLevel = batteryLevel,
    lowBattery = lowBattery,
    firmwareVersion = firmwareVersion,
    groupNo = groupNo,
    lastResultType = lastResultType,
    isAbnormal = isAbnormal,
    abnormalReason = abnormalReason,
    activeBindingId = activeBindingId?.toString()
)

internal fun CachedTagEntity.toModel() = Tag(
    tagId = tagId,
    siteId = siteId?.let(UUID::fromString),
    stationId = stationId,
    registeredAt = registeredAtEpochMillis?.let(Instant::ofEpochMilli),
    firstSeenAt = firstSeenAtEpochMillis?.let(Instant::ofEpochMilli),
    lastSeenAt = lastSeenAtEpochMillis?.let(Instant::ofEpochMilli),
    online = online,
    batteryRaw = batteryRaw,
    batteryVoltage = batteryVoltage,
    batteryLevel = batteryLevel,
    lowBattery = lowBattery,
    firmwareVersion = firmwareVersion,
    groupNo = groupNo,
    lastResultType = lastResultType,
    isAbnormal = isAbnormal,
    abnormalReason = abnormalReason,
    activeBindingId = activeBindingId?.let(UUID::fromString)
)
