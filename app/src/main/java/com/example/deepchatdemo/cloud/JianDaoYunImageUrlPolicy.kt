package com.example.deepchatdemo.cloud

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class JianDaoYunImageUrlPolicy {
    fun resolve(rawUrl: String): HttpUrl? {
        val input = rawUrl.trim()
        if (input.isEmpty() || input.length > MAX_URL_LENGTH) return null
        if (input.any { it.isWhitespace() || it <= '\u001f' || it == '\u007f' }) return null
        if ('\\' in input) return null
        val candidate = input.toHttpUrlOrNull() ?: return null
        if (candidate.scheme != "https" || candidate.host != IMAGE_HOST || candidate.port != 443) {
            return null
        }
        if (candidate.username.isNotEmpty() || candidate.password.isNotEmpty()) return null
        if (candidate.fragment != null || candidate.encodedPath.trim('/').isEmpty()) return null
        return candidate
    }

    fun sanitize(rawUrl: String): String = resolve(rawUrl)?.toString().orEmpty()

    private companion object {
        const val IMAGE_HOST = "files.jiandaoyun.com"
        const val MAX_URL_LENGTH = 8_192
    }
}

internal fun newJianDaoYunImageHttpClient(
    imageUrlPolicy: JianDaoYunImageUrlPolicy
): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
        val request = chain.request()
        val safeUrl = imageUrlPolicy.resolve(request.url.toString())
        if (safeUrl != request.url) {
            throw IOException("Blocked image request outside the JianDaoYun media origin.")
        }
        chain.proceed(request)
    }
    .build()
