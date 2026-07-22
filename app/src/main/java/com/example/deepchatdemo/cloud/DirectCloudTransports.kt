package com.example.deepchatdemo.cloud

import com.example.deepchatdemo.BuildConfig
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject

data class DirectCloudResponse(
    val statusCode: Int,
    val body: String,
    val retryAfterHeader: String?
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

class DirectCloudConfigurationException(provider: String) :
    IOException("$provider 云服务配置不完整，请重新构建应用。")

class DirectCloudResponseTooLargeException(
    val maximumBytes: Long
) : IOException("云服务返回的数据过大，已停止读取以保护手机内存。")

interface OpenAiRelayTransport {
    @Throws(IOException::class)
    fun postResponses(jsonBody: String): DirectCloudResponse
}

class OkHttpOpenAiRelayTransport(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = openAiClient()
) : OpenAiRelayTransport {
    override fun postResponses(jsonBody: String): DirectCloudResponse {
        val resolvedBaseUrl = requireHttpsBaseUrl(baseUrl, "OpenAI")
        val resolvedApiKey = requireSecret(apiKey, "OpenAI")
        val requestUrl = resolvedBaseUrl.newBuilder().addPathSegment("responses").build()
        val origin = resolvedBaseUrl.originString()
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", JSON_MEDIA_TYPE_STRING)
            .header("Authorization", "Bearer $resolvedApiKey")
            .header("Cache-Control", "no-store")
            .header("Content-Type", JSON_MEDIA_TYPE_STRING)
            .header("Origin", origin)
            .header("Referer", "${resolvedBaseUrl.toString().trimEnd('/')}/")
            .header("User-Agent", USER_AGENT)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.executeBounded(request, resolvedApiKey)
    }

    companion object {
        fun fromBuildConfig(): OpenAiRelayTransport = OkHttpOpenAiRelayTransport(
            baseUrl = BuildConfig.OPENAI_BASE_URL,
            apiKey = BuildConfig.OPENAI_API_KEY
        )

        fun openAiClient(): OkHttpClient = baseClientBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

enum class JianDaoYunApiRoute(internal val pathSegments: List<String>) {
    ENTRY_LIST(listOf("v5", "app", "entry", "list")),
    WIDGET_LIST(listOf("v5", "app", "entry", "widget", "list")),
    DATA_LIST(listOf("v5", "app", "entry", "data", "list"))
}

interface JianDaoYunTransport {
    @Throws(IOException::class)
    fun postJson(route: JianDaoYunApiRoute, jsonBody: String): DirectCloudResponse
}

class OkHttpJianDaoYunTransport(
    private val baseUrl: String,
    private val apiKey: String,
    private val appId: String,
    private val entryId: String,
    private val client: OkHttpClient = jiandaoYunClient()
) : JianDaoYunTransport {
    override fun postJson(
        route: JianDaoYunApiRoute,
        jsonBody: String
    ): DirectCloudResponse {
        val resolvedBaseUrl = requireHttpsBaseUrl(baseUrl, "简道云")
        val resolvedApiKey = requireSecret(apiKey, "简道云")
        val resolvedAppId = requireIdentifier(appId, "简道云")
        val payload = runCatching { JSONObject(jsonBody) }
            .getOrElse { throw IOException("简道云请求不是有效 JSON 对象。", it) }
            .put("app_id", resolvedAppId)
        if (route == JianDaoYunApiRoute.ENTRY_LIST) {
            payload.remove("entry_id")
        } else {
            payload.put("entry_id", requireIdentifier(entryId, "简道云"))
        }

        val requestUrl = resolvedBaseUrl.newBuilder().apply {
            route.pathSegments.forEach(::addPathSegment)
        }.build()
        val request = Request.Builder()
            .url(requestUrl)
            .header("Accept", JSON_MEDIA_TYPE_STRING)
            .header("Authorization", "Bearer $resolvedApiKey")
            .header("Cache-Control", "no-store")
            .header("Content-Type", JSON_MEDIA_TYPE_STRING)
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.executeBounded(request, resolvedApiKey)
    }

    companion object {
        fun fromBuildConfig(): JianDaoYunTransport = OkHttpJianDaoYunTransport(
            baseUrl = BuildConfig.JIANDAOYUN_BASE_URL,
            apiKey = BuildConfig.JIANDAOYUN_API_KEY,
            appId = BuildConfig.JIANDAOYUN_APP_ID,
            entryId = BuildConfig.JIANDAOYUN_ENTRY_ID
        )

        fun jiandaoYunClient(): OkHttpClient = baseClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private fun requireHttpsBaseUrl(value: String, provider: String): HttpUrl {
    val url = value.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw DirectCloudConfigurationException(provider)
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) {
        throw DirectCloudConfigurationException(provider)
    }
    return url
}

private fun requireSecret(value: String, provider: String): String {
    return value.trim().takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }
        ?: throw DirectCloudConfigurationException(provider)
}

private fun requireIdentifier(value: String, provider: String): String {
    return value.trim().takeIf { it.isNotEmpty() && it.length <= 256 && '\n' !in it && '\r' !in it }
        ?: throw DirectCloudConfigurationException(provider)
}

private fun HttpUrl.originString(): String {
    val renderedHost = if (':' in host) "[$host]" else host
    val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    return "$scheme://$renderedHost${if (defaultPort) "" else ":$port"}"
}

private fun baseClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_1_1))
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(true)

private fun OkHttpClient.executeBounded(
    request: Request,
    sensitiveValue: String
): DirectCloudResponse {
    newCall(request).execute().use { response ->
        val maximumBytes = if (response.isSuccessful) {
            MAX_SUCCESS_BODY_BYTES
        } else {
            MAX_ERROR_BODY_BYTES
        }
        return DirectCloudResponse(
            statusCode = response.code,
            body = response.readBoundedBody(maximumBytes).redact(sensitiveValue),
            retryAfterHeader = response.header("Retry-After")
        )
    }
}

private fun String.redact(sensitiveValue: String): String {
    if (sensitiveValue.isEmpty() || !contains(sensitiveValue)) return this
    return replace(sensitiveValue, "[REDACTED]")
}

private fun Response.readBoundedBody(maximumBytes: Long): String {
    val responseBody = body ?: return ""
    val declaredLength = responseBody.contentLength()
    if (declaredLength > maximumBytes) throw DirectCloudResponseTooLargeException(maximumBytes)

    val source = responseBody.source()
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val remaining = maximumBytes + 1L - total
        if (remaining <= 0L) throw DirectCloudResponseTooLargeException(maximumBytes)
        val read = source.read(buffer, minOf(8_192L, remaining))
        if (read == -1L) break
        total += read
        if (total > maximumBytes) throw DirectCloudResponseTooLargeException(maximumBytes)
    }
    return buffer.readString(StandardCharsets.UTF_8)
}

private const val JSON_MEDIA_TYPE_STRING = "application/json"
private const val USER_AGENT = "HighTac-Android/2.0"
private const val MAX_SUCCESS_BODY_BYTES = 8L * 1024L * 1024L
private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
