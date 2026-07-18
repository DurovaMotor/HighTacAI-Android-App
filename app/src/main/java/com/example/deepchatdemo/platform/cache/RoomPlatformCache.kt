package com.example.deepchatdemo.platform.cache

import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.Tag
import java.time.Instant
import java.util.UUID

class RoomPlatformCache(
    private val dao: PlatformCacheDao
) : PlatformCache {
    override suspend fun read(endpoint: String): CachedPlatformSnapshot? {
        val stored = dao.snapshot(endpoint) ?: return null
        val metadata = stored.metadata
        return CachedPlatformSnapshot(
            endpoint = endpoint,
            products = stored.products.map(CachedProductEntity::toModel),
            bindings = stored.bindings.map(CachedBindingEntity::toModel),
            tags = stored.tags.map(CachedTagEntity::toModel),
            productsSynchronizedAt = metadata.productsSynchronizedAtEpochMillis.toInstant(),
            bindingsSynchronizedAt = metadata.bindingsSynchronizedAtEpochMillis.toInstant(),
            tagsSynchronizedAt = metadata.tagsSynchronizedAtEpochMillis.toInstant()
        )
    }

    override suspend fun replaceAll(
        endpoint: String,
        products: List<Product>,
        bindings: List<Binding>,
        tags: List<Tag>,
        synchronizedAt: Instant
    ) {
        val at = synchronizedAt.toEpochMilli()
        dao.replaceAll(
            metadata = CacheMetadataEntity(
                endpoint = endpoint,
                productsSynchronizedAtEpochMillis = at,
                bindingsSynchronizedAtEpochMillis = at,
                tagsSynchronizedAtEpochMillis = at
            ),
            products = products.map { it.toCacheEntity(endpoint) },
            bindings = bindings.filter(Binding::isActive).map { it.toCacheEntity(endpoint) },
            tags = tags.map { it.toCacheEntity(endpoint) }
        )
    }

    override suspend fun replaceProducts(
        endpoint: String,
        products: List<Product>,
        synchronizedAt: Instant
    ) {
        dao.replaceProducts(
            endpoint,
            products.map { it.toCacheEntity(endpoint) },
            synchronizedAt.toEpochMilli()
        )
    }

    override suspend fun replaceBindings(
        endpoint: String,
        bindings: List<Binding>,
        synchronizedAt: Instant
    ) {
        dao.replaceBindings(
            endpoint,
            bindings.filter(Binding::isActive).map { it.toCacheEntity(endpoint) },
            synchronizedAt.toEpochMilli()
        )
    }

    override suspend fun replaceTags(
        endpoint: String,
        tags: List<Tag>,
        synchronizedAt: Instant
    ) {
        dao.replaceTags(
            endpoint,
            tags.map { it.toCacheEntity(endpoint) },
            synchronizedAt.toEpochMilli()
        )
    }

    override suspend fun upsertBinding(
        endpoint: String,
        binding: Binding,
        synchronizedAt: Instant
    ) {
        if (binding.isActive) {
            dao.upsertBinding(
                endpoint,
                binding.toCacheEntity(endpoint),
                synchronizedAt.toEpochMilli()
            )
        } else {
            removeBinding(endpoint, binding.id, synchronizedAt)
        }
    }

    override suspend fun mergeBindings(
        endpoint: String,
        bindings: List<Binding>,
        synchronizedAt: Instant
    ) {
        dao.mergeBindings(
            endpoint,
            bindings.filter(Binding::isActive).map { it.toCacheEntity(endpoint) },
            synchronizedAt.toEpochMilli()
        )
    }

    override suspend fun replaceBinding(
        endpoint: String,
        removedBindingId: UUID,
        createdBinding: Binding,
        synchronizedAt: Instant
    ) {
        if (createdBinding.isActive) {
            dao.replaceBinding(
                endpoint,
                removedBindingId.toString(),
                createdBinding.toCacheEntity(endpoint),
                synchronizedAt.toEpochMilli()
            )
        } else {
            removeBinding(endpoint, removedBindingId, synchronizedAt)
        }
    }

    override suspend fun removeBinding(
        endpoint: String,
        bindingId: UUID,
        synchronizedAt: Instant
    ) {
        dao.removeBinding(endpoint, bindingId.toString(), synchronizedAt.toEpochMilli())
    }

    override suspend fun upsertTag(endpoint: String, tag: Tag, synchronizedAt: Instant) {
        dao.upsertTag(endpoint, tag.toCacheEntity(endpoint), synchronizedAt.toEpochMilli())
    }
}

private fun Long?.toInstant(): Instant? = this?.let(Instant::ofEpochMilli)
