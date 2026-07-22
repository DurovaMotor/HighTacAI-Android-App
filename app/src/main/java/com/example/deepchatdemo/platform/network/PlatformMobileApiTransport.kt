package com.example.deepchatdemo.platform.network

import android.content.Context
import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.config.SharedPreferencesPlatformConfigStore
import com.example.deepchatdemo.platform.security.AndroidKeystoreCredentialStore
import com.example.deepchatdemo.platform.security.PlatformAuthTokenProvider
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

enum class PlatformMobileApiRoute(internal val pathSegments: List<String>) {
    OPENAI_RESPONSES(listOf("mobile", "openai", "responses")),
    JIANDAOYUN_ENTRY_LIST(listOf("mobile", "jiandaoyun", "v5", "app", "entry", "list")),
    JIANDAOYUN_WIDGET_LIST(
        listOf("mobile", "jiandaoyun", "v5", "app", "entry", "widget", "list")
    ),
    JIANDAOYUN_DATA_LIST(
        listOf("mobile", "jiandaoyun", "v5", "app", "entry", "data", "list")
    )
}

data class PlatformMobileApiResponse(
    val statusCode: Int,
    val body: String,
    val retryAfterHeader: String?
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

enum class PlatformMobileAuthorizationProblem {
    DEVICE_TOKEN_MISSING,
    DEVICE_TOKEN_REJECTED
}

class PlatformMobileAuthorizationException(
    val problem: PlatformMobileAuthorizationProblem
) : IOException(
    when (problem) {
        PlatformMobileAuthorizationProblem.DEVICE_TOKEN_MISSING ->
            "设备正在自动注册，请稍候重试。"
        PlatformMobileAuthorizationProblem.DEVICE_TOKEN_REJECTED ->
            "设备凭据已失效，应用将自动重新注册，请稍候重试。"
    }
)

class PlatformMobileResponseTooLargeException(
    val maximumBytes: Long
) : IOException("本机平台返回的数据过大，已停止读取以保护手机内存。")

interface PlatformMobileApiTransport {
    fun hasApprovedDeviceToken(): Boolean

    @Throws(IOException::class)
    fun postJson(route: PlatformMobileApiRoute, jsonBody: String): PlatformMobileApiResponse
}

class OkHttpPlatformMobileApiTransport(
    private val endpointProvider: PlatformEndpointProvider,
    private val tokenProvider: PlatformAuthTokenProvider,
    private val client: OkHttpClient = defaultClient()
) : PlatformMobileApiTransport {
    override fun hasApprovedDeviceToken(): Boolean =
        tokenProvider.deviceToken()?.use { true } ?: false

    override fun postJson(
        route: PlatformMobileApiRoute,
        jsonBody: String
    ): PlatformMobileApiResponse {
        val token = tokenProvider.deviceToken()
            ?: throw PlatformMobileAuthorizationException(
                PlatformMobileAuthorizationProblem.DEVICE_TOKEN_MISSING
            )
        val url = endpointProvider.currentEndpoint().apiBaseUrl.newBuilder().apply {
            route.pathSegments.forEach(::addPathSegment)
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", JSON_MEDIA_TYPE_STRING)
            .header("Cache-Control", "no-store")
            .header("Content-Type", JSON_MEDIA_TYPE_STRING)
            .header("User-Agent", USER_AGENT)
            .apply { token.use { header("Authorization", "Bearer $it") } }
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw PlatformMobileAuthorizationException(
                    PlatformMobileAuthorizationProblem.DEVICE_TOKEN_REJECTED
                )
            }
            val maximumBytes = if (response.isSuccessful) {
                MAX_SUCCESS_BODY_BYTES
            } else {
                MAX_ERROR_BODY_BYTES
            }
            val body = readBoundedBody(response, maximumBytes)
            return PlatformMobileApiResponse(
                statusCode = response.code,
                body = body,
                retryAfterHeader = response.header("Retry-After")
            )
        }
    }

    private fun readBoundedBody(response: Response, maximumBytes: Long): String {
        val body = response.body ?: return ""
        val declaredLength = body.contentLength()
        if (declaredLength > maximumBytes) {
            throw PlatformMobileResponseTooLargeException(maximumBytes)
        }

        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val remaining = maximumBytes + 1L - total
            if (remaining <= 0L) throw PlatformMobileResponseTooLargeException(maximumBytes)
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) break
            total += read
            if (total > maximumBytes) {
                throw PlatformMobileResponseTooLargeException(maximumBytes)
            }
        }
        return buffer.readString(StandardCharsets.UTF_8)
    }

    companion object {
        private const val JSON_MEDIA_TYPE_STRING = "application/json"
        private const val USER_AGENT = "HighTac-Android/2.0"
        private const val MAX_SUCCESS_BODY_BYTES = 8L * 1024L * 1024L
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

object AndroidPlatformMobileApiTransportFactory {
    fun create(context: Context): PlatformMobileApiTransport {
        val applicationContext = context.applicationContext
        val configStore = SharedPreferencesPlatformConfigStore(applicationContext)
        val credentials = AndroidKeystoreCredentialStore(applicationContext, configStore)
        return OkHttpPlatformMobileApiTransport(
            endpointProvider = configStore,
            tokenProvider = credentials
        )
    }
}
