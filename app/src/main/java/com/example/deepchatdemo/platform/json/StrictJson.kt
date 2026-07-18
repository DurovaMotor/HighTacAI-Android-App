package com.example.deepchatdemo.platform.json

import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

class PlatformJsonException(
    val jsonPath: String,
    reason: String,
    cause: Throwable? = null
) : IllegalArgumentException("Invalid platform JSON at $jsonPath: $reason", cause)

object PlatformValueRules {
    private val UUID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-" +
            "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    )
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val STATION_ID_PATTERN = Regex("^90A9F[0-9A-F]{7}$")
    private val TAG_ID_PATTERN = Regex("^AD1[0-9A-F]{9}$")
    private val ERROR_CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,63}$")
    private val NOTICE_EVENT_PATTERN = Regex("^[a-z][a-z0-9_.]{1,127}$")
    private val PRODUCT_CODE_DASH_PATTERN = Regex(
        "[\u2010\u2011\u2012\u2013\u2014\u2212\uFF0D]"
    )

    fun requireUuid(value: String, label: String = "UUID"): UUID {
        require(UUID_PATTERN.matches(value)) { "$label is not a canonical UUID." }
        return UUID.fromString(value)
    }

    fun requireSha256(value: String, label: String = "SHA-256"): String {
        require(SHA256_PATTERN.matches(value)) { "$label must be lowercase hexadecimal." }
        return value
    }

    fun requireStationId(value: String): String {
        require(STATION_ID_PATTERN.matches(value)) { "Station ID has an invalid format." }
        return value
    }

    fun requireTagId(value: String): String {
        require(TAG_ID_PATTERN.matches(value)) { "Tag ID has an invalid format." }
        return value
    }

    fun requireProductCode(value: String): String {
        require(value.isNotEmpty() && value.length <= 128) { "Product code length is invalid." }
        require(value == value.trim()) { "Product code must be trimmed." }
        return value
    }

    fun normalizeProductCode(value: String): String = requireProductCode(
        value.trim().replace(PRODUCT_CODE_DASH_PATTERN, "-").uppercase()
    )

    fun requireErrorCode(value: String): String {
        require(ERROR_CODE_PATTERN.matches(value)) { "Error code has an invalid format." }
        return value
    }

    fun requireEventName(value: String): String {
        require(NOTICE_EVENT_PATTERN.matches(value)) { "Event name has an invalid format." }
        return value
    }

    fun requireBatteryLevel(value: Int?): Int? {
        require(value == null || value in setOf(0, 10, 30, 60, 80, 90, 100)) {
            "Battery level is not a contract value."
        }
        return value
    }

    fun parseTimestamp(value: String): Instant {
        require(value.length <= 64) { "Timestamp is too long." }
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Timestamp is not RFC 3339 with an offset.", error)
        }
    }
}

internal object StrictJson {
    const val MAX_DOCUMENT_CHARS = 8 * 1024 * 1024

    fun parseObject(text: String, maxChars: Int = MAX_DOCUMENT_CHARS): StrictJsonObject {
        if (text.length > maxChars) {
            throw PlatformJsonException("$", "document exceeds the size limit")
        }
        if (text.isBlank()) {
            throw PlatformJsonException("$", "document is empty")
        }

        try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (value !is JSONObject) {
                throw PlatformJsonException("$", "top-level value must be an object")
            }
            if (tokener.nextClean() != '\u0000') {
                throw PlatformJsonException("$", "trailing content is not allowed")
            }
            return StrictJsonObject(value, "$")
        } catch (error: PlatformJsonException) {
            throw error
        } catch (error: JSONException) {
            throw PlatformJsonException("$", "document is not valid JSON", error)
        }
    }
}

internal class StrictJsonObject(
    internal val value: JSONObject,
    private val path: String
) {
    fun shape(required: Set<String>, optional: Set<String> = emptySet()): StrictJsonObject {
        val actual = value.keys().asSequence().toSet()
        val missing = required - actual
        if (missing.isNotEmpty()) {
            throw error("missing required field ${missing.sorted().first()}")
        }
        val unknown = actual - required - optional
        if (unknown.isNotEmpty()) {
            throw error("unknown field ${unknown.sorted().first()}")
        }
        return this
    }

    fun raw(name: String): Any {
        if (!value.has(name)) throw childError(name, "field is required")
        return try {
            value.get(name)
        } catch (error: JSONException) {
            throw childError(name, "field could not be read", error)
        }
    }

    fun string(
        name: String,
        minLength: Int = 0,
        maxLength: Int = Int.MAX_VALUE,
        pattern: Regex? = null,
        constant: String? = null
    ): String {
        val raw = raw(name)
        if (raw !is String) throw childError(name, "expected a string")
        if (raw.length !in minLength..maxLength) {
            throw childError(name, "string length is outside the contract range")
        }
        if (pattern != null && !pattern.matches(raw)) {
            throw childError(name, "string does not match the contract pattern")
        }
        if (constant != null && raw != constant) {
            throw childError(name, "string does not match the contract constant")
        }
        return raw
    }

    fun nullableString(
        name: String,
        minLength: Int = 0,
        maxLength: Int = Int.MAX_VALUE,
        pattern: Regex? = null
    ): String? {
        val raw = raw(name)
        if (raw === JSONObject.NULL) return null
        if (raw !is String) throw childError(name, "expected a string or null")
        if (raw.length !in minLength..maxLength) {
            throw childError(name, "string length is outside the contract range")
        }
        if (pattern != null && !pattern.matches(raw)) {
            throw childError(name, "string does not match the contract pattern")
        }
        return raw
    }

    fun boolean(name: String): Boolean {
        val raw = raw(name)
        if (raw !is Boolean) throw childError(name, "expected a boolean")
        return raw
    }

    fun int(name: String, minimum: Int = Int.MIN_VALUE, maximum: Int = Int.MAX_VALUE): Int {
        val number = integral(name, raw(name))
        if (number !in minimum.toLong()..maximum.toLong()) {
            throw childError(name, "integer is outside the contract range")
        }
        return number.toInt()
    }

    fun nullableInt(
        name: String,
        minimum: Int = Int.MIN_VALUE,
        maximum: Int = Int.MAX_VALUE
    ): Int? {
        val raw = raw(name)
        if (raw === JSONObject.NULL) return null
        val number = integral(name, raw)
        if (number !in minimum.toLong()..maximum.toLong()) {
            throw childError(name, "integer is outside the contract range")
        }
        return number.toInt()
    }

    fun long(name: String, minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
        val number = integral(name, raw(name))
        if (number !in minimum..maximum) {
            throw childError(name, "integer is outside the contract range")
        }
        return number
    }

    fun nullableLong(
        name: String,
        minimum: Long = Long.MIN_VALUE,
        maximum: Long = Long.MAX_VALUE
    ): Long? {
        val raw = raw(name)
        if (raw === JSONObject.NULL) return null
        val number = integral(name, raw)
        if (number !in minimum..maximum) {
            throw childError(name, "integer is outside the contract range")
        }
        return number
    }

    fun double(name: String, minimum: Double = -Double.MAX_VALUE, maximum: Double = Double.MAX_VALUE): Double {
        val raw = raw(name)
        if (raw !is Number) throw childError(name, "expected a number")
        val number = raw.toDouble()
        if (!number.isFinite() || number !in minimum..maximum) {
            throw childError(name, "number is outside the contract range")
        }
        return number
    }

    fun nullableDouble(
        name: String,
        minimum: Double = -Double.MAX_VALUE,
        maximum: Double = Double.MAX_VALUE
    ): Double? {
        val raw = raw(name)
        if (raw === JSONObject.NULL) return null
        if (raw !is Number) throw childError(name, "expected a number or null")
        val number = raw.toDouble()
        if (!number.isFinite() || number !in minimum..maximum) {
            throw childError(name, "number is outside the contract range")
        }
        return number
    }

    fun uuid(name: String): UUID {
        val text = string(name)
        return checked(name) { PlatformValueRules.requireUuid(text, name) }
    }

    fun nullableUuid(name: String): UUID? {
        val text = nullableString(name) ?: return null
        return checked(name) { PlatformValueRules.requireUuid(text, name) }
    }

    fun instant(name: String): Instant {
        val text = string(name, minLength = 1, maxLength = 64)
        return checked(name) { PlatformValueRules.parseTimestamp(text) }
    }

    fun nullableInstant(name: String): Instant? {
        val text = nullableString(name, minLength = 1, maxLength = 64) ?: return null
        return checked(name) { PlatformValueRules.parseTimestamp(text) }
    }

    inline fun <reified T : Enum<T>> enum(name: String): T {
        val text = string(name)
        return enumValues<T>().firstOrNull { it.name == text }
            ?: throw childError(name, "unknown enum value")
    }

    fun <T> mappedString(name: String, values: Iterable<T>, wireName: (T) -> String): T {
        val text = string(name)
        return values.firstOrNull { wireName(it) == text }
            ?: throw childError(name, "unknown enum value")
    }

    fun objectValue(name: String): StrictJsonObject {
        val raw = raw(name)
        if (raw !is JSONObject) throw childError(name, "expected an object")
        return StrictJsonObject(raw, childPath(name))
    }

    fun nullableObject(name: String): StrictJsonObject? {
        val raw = raw(name)
        if (raw === JSONObject.NULL) return null
        if (raw !is JSONObject) throw childError(name, "expected an object or null")
        return StrictJsonObject(raw, childPath(name))
    }

    fun array(name: String, minItems: Int = 0, maxItems: Int = Int.MAX_VALUE): StrictJsonArray {
        val raw = raw(name)
        if (raw !is JSONArray) throw childError(name, "expected an array")
        if (raw.length() !in minItems..maxItems) {
            throw childError(name, "array length is outside the contract range")
        }
        return StrictJsonArray(raw, childPath(name))
    }

    private fun integral(name: String, raw: Any): Long {
        if (raw !is Number) throw childError(name, "expected an integer")
        return try {
            BigDecimal(raw.toString()).longValueExact()
        } catch (error: ArithmeticException) {
            throw childError(name, "expected an exact integer", error)
        } catch (error: NumberFormatException) {
            throw childError(name, "expected an exact integer", error)
        }
    }

    private inline fun <T> checked(name: String, block: () -> T): T {
        return try {
            block()
        } catch (error: IllegalArgumentException) {
            throw childError(name, error.message ?: "value is invalid", error)
        }
    }

    private fun childPath(name: String): String = "$path.$name"

    private fun error(reason: String, cause: Throwable? = null): PlatformJsonException =
        PlatformJsonException(path, reason, cause)

    private fun childError(
        name: String,
        reason: String,
        cause: Throwable? = null
    ): PlatformJsonException = PlatformJsonException(childPath(name), reason, cause)
}

internal class StrictJsonArray(
    private val value: JSONArray,
    private val path: String
) {
    val size: Int
        get() = value.length()

    fun objectAt(index: Int): StrictJsonObject {
        val raw = rawAt(index)
        if (raw !is JSONObject) {
            throw PlatformJsonException("$path[$index]", "expected an object")
        }
        return StrictJsonObject(raw, "$path[$index]")
    }

    fun stringAt(index: Int, minLength: Int = 0, maxLength: Int = Int.MAX_VALUE): String {
        val raw = rawAt(index)
        if (raw !is String) {
            throw PlatformJsonException("$path[$index]", "expected a string")
        }
        if (raw.length !in minLength..maxLength) {
            throw PlatformJsonException("$path[$index]", "string length is outside the contract range")
        }
        return raw
    }

    private fun rawAt(index: Int): Any {
        return try {
            value.get(index)
        } catch (error: JSONException) {
            throw PlatformJsonException("$path[$index]", "array item could not be read", error)
        }
    }
}

internal fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)
