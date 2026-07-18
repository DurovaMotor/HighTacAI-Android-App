package com.example.deepchatdemo.platform.cache

import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.Tag
import java.time.Instant
import java.util.UUID

data class CachedPlatformSnapshot(
    val endpoint: String,
    val products: List<Product>,
    val bindings: List<Binding>,
    val tags: List<Tag>,
    val productsSynchronizedAt: Instant?,
    val bindingsSynchronizedAt: Instant?,
    val tagsSynchronizedAt: Instant?
)

interface PlatformCache {
    suspend fun read(endpoint: String): CachedPlatformSnapshot?

    suspend fun replaceAll(
        endpoint: String,
        products: List<Product>,
        bindings: List<Binding>,
        tags: List<Tag>,
        synchronizedAt: Instant
    )

    suspend fun replaceProducts(
        endpoint: String,
        products: List<Product>,
        synchronizedAt: Instant
    )

    suspend fun replaceBindings(
        endpoint: String,
        bindings: List<Binding>,
        synchronizedAt: Instant
    )

    suspend fun replaceTags(
        endpoint: String,
        tags: List<Tag>,
        synchronizedAt: Instant
    )

    suspend fun upsertBinding(endpoint: String, binding: Binding, synchronizedAt: Instant)

    suspend fun mergeBindings(
        endpoint: String,
        bindings: List<Binding>,
        synchronizedAt: Instant
    )

    suspend fun replaceBinding(
        endpoint: String,
        removedBindingId: UUID,
        createdBinding: Binding,
        synchronizedAt: Instant
    )

    suspend fun removeBinding(endpoint: String, bindingId: UUID, synchronizedAt: Instant)

    suspend fun upsertTag(endpoint: String, tag: Tag, synchronizedAt: Instant)
}

object NoOpPlatformCache : PlatformCache {
    override suspend fun read(endpoint: String): CachedPlatformSnapshot? = null

    override suspend fun replaceAll(
        endpoint: String,
        products: List<Product>,
        bindings: List<Binding>,
        tags: List<Tag>,
        synchronizedAt: Instant
    ) = Unit

    override suspend fun replaceProducts(
        endpoint: String,
        products: List<Product>,
        synchronizedAt: Instant
    ) = Unit

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
    ) = Unit

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
