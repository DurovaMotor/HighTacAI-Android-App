package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.platform.config.PlatformEndpoint
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class PlatformImageUrlResolver(
    private val endpointProvider: PlatformEndpointProvider
) {
    fun resolve(rawUrl: String): HttpUrl? {
        val input = rawUrl.trim()
        if (input.isEmpty() || input.length > MAX_URL_LENGTH) return null
        if (input.any { it.isWhitespace() || it <= '\u001f' || it == '\u007f' }) return null
        if ('\\' in input) return null

        val endpoint = runCatching(endpointProvider::currentEndpoint).getOrNull() ?: return null
        return resolve(input, endpoint)
    }

    fun sanitize(rawUrl: String): String = resolve(rawUrl)?.toString().orEmpty()

    private fun resolve(input: String, endpoint: PlatformEndpoint): HttpUrl? {
        val platformOrigin = endpoint.serverBaseUrl
        val candidate = when {
            SCHEME_PREFIX.containsMatchIn(input) -> {
                if (!input.startsWith("http://", ignoreCase = true) &&
                    !input.startsWith("https://", ignoreCase = true)
                ) {
                    return null
                }
                input.toHttpUrlOrNull()
            }
            input.startsWith("//") -> null
            input.startsWith(MEDIA_PATH_PREFIX) ||
                input.startsWith(RELATIVE_MEDIA_PATH_PREFIX) ||
                input.startsWith("./$RELATIVE_MEDIA_PATH_PREFIX") ->
                platformOrigin.resolve(input)
            else -> null
        } ?: return null

        if (candidate.scheme != platformOrigin.scheme ||
            candidate.host != platformOrigin.host ||
            candidate.port != platformOrigin.port
        ) {
            return null
        }
        if (candidate.username.isNotEmpty() || candidate.password.isNotEmpty()) return null
        if (candidate.fragment != null) return null

        val mediaTokenPath = candidate.encodedPath.removePrefix(MEDIA_PATH_PREFIX)
        if (mediaTokenPath == candidate.encodedPath || mediaTokenPath.trim('/').isBlank()) {
            return null
        }
        return candidate
    }

    private companion object {
        const val MAX_URL_LENGTH = 8_192
        const val MEDIA_PATH_PREFIX = "/api/v1/mobile/media/"
        const val RELATIVE_MEDIA_PATH_PREFIX = "api/v1/mobile/media/"
        val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    }
}

internal fun newPlatformImageHttpClient(
    imageUrlResolver: PlatformImageUrlResolver
): OkHttpClient {
    return OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val safeUrl = imageUrlResolver.resolve(request.url.toString())
            if (safeUrl != request.url) {
                throw IOException("Blocked image request outside the configured HighTac platform.")
            }
            chain.proceed(request)
        }
        .build()
}
