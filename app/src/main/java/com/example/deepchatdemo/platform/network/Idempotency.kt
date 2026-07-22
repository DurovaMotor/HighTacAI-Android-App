package com.example.deepchatdemo.platform.network

import java.util.UUID

@JvmInline
value class IdempotencyKey private constructor(val value: String) {
    companion object {
        private val UUID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        fun fromUuid(uuid: UUID): IdempotencyKey = IdempotencyKey(uuid.toString())

        fun parse(value: String): IdempotencyKey {
            require(UUID_PATTERN.matches(value)) {
                "Idempotency key must use canonical UUID syntax."
            }
            val parsed = runCatching { UUID.fromString(value) }
                .getOrElse { throw IllegalArgumentException("Idempotency key must be a UUID.", it) }
            return fromUuid(parsed)
        }
    }
}

fun interface UuidSource {
    fun next(): UUID
}

class IdempotencyKeyFactory(
    private val uuidSource: UuidSource = UuidSource(UUID::randomUUID)
) {
    fun create(): IdempotencyKey = IdempotencyKey.fromUuid(uuidSource.next())
}
