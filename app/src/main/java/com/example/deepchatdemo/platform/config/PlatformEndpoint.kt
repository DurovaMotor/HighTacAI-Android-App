package com.example.deepchatdemo.platform.config

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PlatformEndpoint internal constructor(
    val serverBaseUrl: HttpUrl,
    val apiBaseUrl: HttpUrl,
    val eventsWebSocketUrl: HttpUrl
) {
    val canonicalServerUrl: String
        get() = serverBaseUrl.toString().trimEnd('/')
}

enum class PlatformUrlProblem {
    EMPTY,
    MALFORMED,
    UNSUPPORTED_SCHEME,
    CREDENTIALS_NOT_ALLOWED,
    PATH_NOT_ALLOWED,
    QUERY_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    LOOPBACK_HOST,
    UNSPECIFIED_HOST,
    EMULATOR_ONLY_HOST,
    CLEARTEXT_HOST_NOT_LAN
}

sealed interface PlatformUrlValidation {
    data class Valid(val endpoint: PlatformEndpoint) : PlatformUrlValidation

    data class Invalid(val problem: PlatformUrlProblem) : PlatformUrlValidation
}

object PlatformUrlValidator {
    const val DEFAULT_SERVER_URL = "http://192.168.1.105:8088"

    private val LOOPBACK_HOSTS = setOf(
        "localhost",
        "localhost.localdomain",
        "::1",
        "0:0:0:0:0:0:0:1"
    )
    private val UNSPECIFIED_HOSTS = setOf(
        "0.0.0.0",
        "::",
        "0:0:0:0:0:0:0:0"
    )
    private val EMULATOR_ONLY_HOSTS = setOf(
        "10.0.2.2",
        "10.0.3.2",
        "host.docker.internal",
        "gateway.docker.internal"
    )
    private val LOCAL_DNS_SUFFIXES = setOf(
        ".local",
        ".lan",
        ".internal",
        ".home.arpa"
    )

    fun validateForWifiProduction(rawUrl: String): PlatformUrlValidation {
        val input = rawUrl.trim()
        if (input.isEmpty()) return PlatformUrlValidation.Invalid(PlatformUrlProblem.EMPTY)

        val url = input.toHttpUrlOrNull()
            ?: return PlatformUrlValidation.Invalid(PlatformUrlProblem.MALFORMED)
        if (url.scheme != "http" && url.scheme != "https") {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.UNSUPPORTED_SCHEME)
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.CREDENTIALS_NOT_ALLOWED)
        }
        if (url.encodedPath != "/") {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.PATH_NOT_ALLOWED)
        }
        if (url.query != null) {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.QUERY_NOT_ALLOWED)
        }
        if (url.fragment != null) {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.FRAGMENT_NOT_ALLOWED)
        }

        val host = url.host.lowercase().trimEnd('.')
        if (host in LOOPBACK_HOSTS || host.endsWith(".localhost") || host.startsWith("127.")) {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.LOOPBACK_HOST)
        }
        if (host in UNSPECIFIED_HOSTS) {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.UNSPECIFIED_HOST)
        }
        if (host in EMULATOR_ONLY_HOSTS) {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.EMULATOR_ONLY_HOST)
        }
        classifyIpv6Literal(host)?.let { problem ->
            return PlatformUrlValidation.Invalid(problem)
        }
        if (url.scheme == "http" && !isLanCleartextHost(host)) {
            return PlatformUrlValidation.Invalid(PlatformUrlProblem.CLEARTEXT_HOST_NOT_LAN)
        }

        val serverBase = url.newBuilder()
            .encodedPath("/")
            .build()
        val apiBase = serverBase.newBuilder()
            .addPathSegment("api")
            .addPathSegment("v1")
            .addPathSegment("")
            .build()
        // OkHttp's WebSocket API intentionally accepts an http(s) handshake URL.
        val webSocket = serverBase.newBuilder()
            .addPathSegment("api")
            .addPathSegment("v1")
            .addPathSegment("ws")
            .addPathSegment("events")
            .build()
        return PlatformUrlValidation.Valid(
            PlatformEndpoint(
                serverBaseUrl = serverBase,
                apiBaseUrl = apiBase,
                eventsWebSocketUrl = webSocket
            )
        )
    }

    fun requireForWifiProduction(rawUrl: String): PlatformEndpoint {
        return when (val result = validateForWifiProduction(rawUrl)) {
            is PlatformUrlValidation.Valid -> result.endpoint
            is PlatformUrlValidation.Invalid -> throw IllegalArgumentException(
                "Platform server URL is not valid for Wi-Fi production: ${result.problem}."
            )
        }
    }

    private fun classifyIpv6Literal(host: String): PlatformUrlProblem? {
        if (':' !in host) return null
        val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return null
        return when {
            address.isLoopbackAddress -> PlatformUrlProblem.LOOPBACK_HOST
            address.isAnyLocalAddress -> PlatformUrlProblem.UNSPECIFIED_HOST
            else -> null
        }
    }

    private fun isLanCleartextHost(host: String): Boolean {
        if (':' in host) {
            val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()
                ?: return false
            val bytes = address.address
            val isUniqueLocal = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
            return address.isLinkLocalAddress || isUniqueLocal
        }

        parseIpv4(host)?.let { octets ->
            return when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                octets[0] == 169 && octets[1] == 254 -> true
                else -> false
            }
        }

        return '.' !in host || LOCAL_DNS_SUFFIXES.any(host::endsWith)
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            octets[index] = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return octets
    }
}
