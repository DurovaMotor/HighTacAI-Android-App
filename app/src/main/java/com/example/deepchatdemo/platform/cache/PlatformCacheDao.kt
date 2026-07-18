package com.example.deepchatdemo.platform.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class CachedDatabaseSnapshot(
    val metadata: CacheMetadataEntity,
    val products: List<CachedProductEntity>,
    val bindings: List<CachedBindingEntity>,
    val tags: List<CachedTagEntity>
)

@Dao
interface PlatformCacheDao {
    @Query("SELECT * FROM cache_metadata WHERE endpoint = :endpoint")
    suspend fun metadata(endpoint: String): CacheMetadataEntity?

    @Query("SELECT * FROM cached_products WHERE endpoint = :endpoint ORDER BY product_code, id")
    suspend fun products(endpoint: String): List<CachedProductEntity>

    @Query(
        "SELECT * FROM cached_active_bindings " +
            "WHERE endpoint = :endpoint ORDER BY product_code, tag_id, id"
    )
    suspend fun bindings(endpoint: String): List<CachedBindingEntity>

    @Query("SELECT * FROM cached_tags WHERE endpoint = :endpoint ORDER BY tag_id")
    suspend fun tags(endpoint: String): List<CachedTagEntity>

    @Transaction
    suspend fun snapshot(endpoint: String): CachedDatabaseSnapshot? {
        val metadata = metadata(endpoint) ?: return null
        return CachedDatabaseSnapshot(
            metadata = metadata,
            products = products(endpoint),
            bindings = bindings(endpoint),
            tags = tags(endpoint)
        )
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<CachedProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBindings(bindings: List<CachedBindingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBinding(binding: CachedBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<CachedTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: CachedTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(metadata: CacheMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureMetadata(metadata: CacheMetadataEntity)

    @Query("DELETE FROM cached_products WHERE endpoint = :endpoint")
    suspend fun deleteProducts(endpoint: String)

    @Query("DELETE FROM cached_active_bindings WHERE endpoint = :endpoint")
    suspend fun deleteBindings(endpoint: String)

    @Query("DELETE FROM cached_tags WHERE endpoint = :endpoint")
    suspend fun deleteTags(endpoint: String)

    @Query(
        "DELETE FROM cached_active_bindings " +
            "WHERE endpoint = :endpoint AND (id = :bindingId OR tag_id = :tagId)"
    )
    suspend fun deleteBindingConflicts(endpoint: String, bindingId: String, tagId: String)

    @Query("DELETE FROM cached_active_bindings WHERE endpoint = :endpoint AND id = :bindingId")
    suspend fun deleteBinding(endpoint: String, bindingId: String)

    @Query(
        "UPDATE cache_metadata SET products_synchronized_at_epoch_millis = :at " +
            "WHERE endpoint = :endpoint"
    )
    suspend fun updateProductsSynchronizedAt(endpoint: String, at: Long)

    @Query(
        "UPDATE cache_metadata SET bindings_synchronized_at_epoch_millis = :at " +
            "WHERE endpoint = :endpoint"
    )
    suspend fun updateBindingsSynchronizedAt(endpoint: String, at: Long)

    @Query(
        "UPDATE cache_metadata SET tags_synchronized_at_epoch_millis = :at " +
            "WHERE endpoint = :endpoint"
    )
    suspend fun updateTagsSynchronizedAt(endpoint: String, at: Long)

    @Transaction
    suspend fun replaceAll(
        metadata: CacheMetadataEntity,
        products: List<CachedProductEntity>,
        bindings: List<CachedBindingEntity>,
        tags: List<CachedTagEntity>
    ) {
        deleteProducts(metadata.endpoint)
        deleteBindings(metadata.endpoint)
        deleteTags(metadata.endpoint)
        insertProducts(products)
        insertBindings(bindings)
        insertTags(tags)
        putMetadata(metadata)
    }

    @Transaction
    suspend fun replaceProducts(
        endpoint: String,
        products: List<CachedProductEntity>,
        synchronizedAtEpochMillis: Long
    ) {
        deleteProducts(endpoint)
        insertProducts(products)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateProductsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }

    @Transaction
    suspend fun replaceBindings(
        endpoint: String,
        bindings: List<CachedBindingEntity>,
        synchronizedAtEpochMillis: Long
    ) {
        deleteBindings(endpoint)
        insertBindings(bindings)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateBindingsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }

    @Transaction
    suspend fun replaceTags(
        endpoint: String,
        tags: List<CachedTagEntity>,
        synchronizedAtEpochMillis: Long
    ) {
        deleteTags(endpoint)
        insertTags(tags)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateTagsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }

    @Transaction
    suspend fun upsertBinding(
        endpoint: String,
        binding: CachedBindingEntity,
        synchronizedAtEpochMillis: Long
    ) {
        deleteBindingConflicts(endpoint, binding.id, binding.tagId)
        insertBinding(binding)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateBindingsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }

    @Transaction
    suspend fun mergeBindings(
        endpoint: String,
        bindings: List<CachedBindingEntity>,
        synchronizedAtEpochMillis: Long
    ) {
        bindings.forEach { binding ->
            deleteBindingConflicts(endpoint, binding.id, binding.tagId)
        }
        if (bindings.isNotEmpty()) insertBindings(bindings)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateBindingsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }

    @Transaction
    suspend fun replaceBinding(
        endpoint: String,
        removedBindingId: String,
        binding: CachedBindingEntity,
        synchronizedAtEpochMillis: Long
    ) {
        deleteBinding(endpoint, removedBindingId)
        deleteBindingConflicts(endpoint, binding.id, binding.tagId)
        insertBinding(binding)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateBindingsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }

    @Transaction
    suspend fun removeBinding(
        endpoint: String,
        bindingId: String,
        synchronizedAtEpochMillis: Long
    ) {
        deleteBinding(endpoint, bindingId)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateBindingsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }

    @Transaction
    suspend fun upsertTag(
        endpoint: String,
        tag: CachedTagEntity,
        synchronizedAtEpochMillis: Long
    ) {
        insertTag(tag)
        ensureMetadata(CacheMetadataEntity(endpoint))
        updateTagsSynchronizedAt(endpoint, synchronizedAtEpochMillis)
    }
}
