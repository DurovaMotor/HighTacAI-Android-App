package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.json.PlatformJsonCodec
import com.example.deepchatdemo.platform.json.PlatformJsonException
import com.example.deepchatdemo.platform.json.PlatformValueRules
import com.example.deepchatdemo.platform.model.ApiError
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitRequest
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationCommitResult
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreview
import com.example.deepchatdemo.platform.model.AndroidBindingMigrationPreviewRequest
import com.example.deepchatdemo.platform.model.Binding
import com.example.deepchatdemo.platform.model.BindingCreateRequest
import com.example.deepchatdemo.platform.model.BindingSource
import com.example.deepchatdemo.platform.model.BrokerStatus
import com.example.deepchatdemo.platform.model.DeviceEnrollmentCreated
import com.example.deepchatdemo.platform.model.DeviceEnrollmentRequest
import com.example.deepchatdemo.platform.model.DeviceEnrollmentState
import com.example.deepchatdemo.platform.model.LightCommand
import com.example.deepchatdemo.platform.model.LightCommandRequest
import com.example.deepchatdemo.platform.model.Liveness
import com.example.deepchatdemo.platform.model.Page
import com.example.deepchatdemo.platform.model.PageRequest
import com.example.deepchatdemo.platform.model.Product
import com.example.deepchatdemo.platform.model.ProductDetail
import com.example.deepchatdemo.platform.model.ProductSource
import com.example.deepchatdemo.platform.model.Readiness
import com.example.deepchatdemo.platform.model.RebindRequest
import com.example.deepchatdemo.platform.model.RebindResult
import com.example.deepchatdemo.platform.model.SensitiveString
import com.example.deepchatdemo.platform.model.Station
import com.example.deepchatdemo.platform.model.StationStatus
import com.example.deepchatdemo.platform.model.Tag
import com.example.deepchatdemo.platform.model.TagDetail
import com.example.deepchatdemo.platform.model.TagRegisterRequest
import com.example.deepchatdemo.platform.security.PlatformAuthTokenProvider
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

data class ProductQuery(
    val search: String? = null,
    val productCode: String? = null,
    val productName: String? = null,
    val tagId: String? = null,
    val source: ProductSource? = null,
    val isActive: Boolean? = null
)

data class BindingQuery(
    val productId: UUID? = null,
    val productCode: String? = null,
    val tagId: String? = null,
    val stationId: String? = null,
    val source: BindingSource? = null,
    val isActive: Boolean = true
)

data class TagQuery(
    val search: String? = null,
    val siteId: UUID? = null,
    val stationId: String? = null,
    val online: Boolean? = null,
    val lowBattery: Boolean? = null,
    val abnormal: Boolean? = null,
    val bound: Boolean? = null
)

data class StationQuery(
    val search: String? = null,
    val siteId: UUID? = null,
    val status: StationStatus? = null
)

interface HighTacPlatformApi {
    suspend fun getLiveness(): Liveness

    suspend fun getReadiness(): Readiness

    suspend fun getBrokerStatus(): BrokerStatus

    suspend fun createDeviceEnrollment(
        request: DeviceEnrollmentRequest,
        idempotencyKey: IdempotencyKey
    ): DeviceEnrollmentCreated

    suspend fun getDeviceEnrollment(
        enrollmentId: UUID,
        pollSecret: SensitiveString
    ): DeviceEnrollmentState

    suspend fun listProducts(
        page: PageRequest = PageRequest(),
        query: ProductQuery = ProductQuery()
    ): Page<Product>

    suspend fun getProduct(productId: UUID): ProductDetail

    suspend fun listBindings(
        page: PageRequest = PageRequest(),
        query: BindingQuery = BindingQuery()
    ): Page<Binding>

    suspend fun createBinding(
        request: BindingCreateRequest,
        idempotencyKey: IdempotencyKey
    ): Binding

    suspend fun rebind(
        bindingId: UUID,
        request: RebindRequest,
        idempotencyKey: IdempotencyKey
    ): RebindResult

    suspend fun unbind(bindingId: UUID, idempotencyKey: IdempotencyKey): Binding

    suspend fun previewAndroidBindingMigration(
        request: AndroidBindingMigrationPreviewRequest
    ): AndroidBindingMigrationPreview

    suspend fun commitAndroidBindingMigration(
        request: AndroidBindingMigrationCommitRequest,
        idempotencyKey: IdempotencyKey
    ): AndroidBindingMigrationCommitResult

    suspend fun listTags(
        page: PageRequest = PageRequest(),
        query: TagQuery = TagQuery()
    ): Page<Tag>

    suspend fun getTag(tagId: String): TagDetail

    suspend fun registerTag(
        request: TagRegisterRequest,
        idempotencyKey: IdempotencyKey
    ): Tag

    suspend fun listStations(
        page: PageRequest = PageRequest(),
        query: StationQuery = StationQuery()
    ): Page<Station>

    suspend fun getStation(stationId: String): Station

    suspend fun createLightCommand(
        request: LightCommandRequest,
        idempotencyKey: IdempotencyKey
    ): LightCommand

    suspend fun getLightCommand(commandId: UUID): LightCommand

    suspend fun allOff(
        stationId: String,
        idempotencyKey: IdempotencyKey
    ): LightCommand
}

sealed class PlatformClientException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class PlatformAuthenticationException : PlatformClientException(
    "The platform rejected the Android request."
)

class PlatformTransportException(cause: IOException) : PlatformClientException(
    "The HighTac platform could not be reached.",
    cause
)

class PlatformProtocolException(cause: Throwable? = null) : PlatformClientException(
    "The HighTac platform returned a response that does not match the contract.",
    cause
)

class PlatformApiException(
    val statusCode: Int,
    val apiError: ApiError?,
    val retryAfterSeconds: Long?
) : PlatformClientException(
    "HighTac platform request failed with HTTP $statusCode" +
        (apiError?.code?.let { " ($it)" } ?: ".")
) {
    val isAuthenticationFailure: Boolean
        get() = statusCode == 401 || statusCode == 403

    val isUnavailable: Boolean
        get() = statusCode == 408 || statusCode == 429 || statusCode in 500..599
}

class OkHttpHighTacPlatformApi(
    private val endpointProvider: PlatformEndpointProvider,
    private val tokenProvider: PlatformAuthTokenProvider,
    private val client: OkHttpClient = defaultClient()
) : HighTacPlatformApi {
    override suspend fun getLiveness(): Liveness = get(
        url = apiUrl("health", "live"),
        authenticated = false,
        parser = PlatformJsonCodec::parseLiveness
    )

    override suspend fun getReadiness(): Readiness = get(
        url = apiUrl("health", "ready"),
        authenticated = false,
        acceptedStatusCodes = setOf(200, 503),
        parseAcceptedErrorStatus = true,
        parser = PlatformJsonCodec::parseReadiness
    )

    override suspend fun getBrokerStatus(): BrokerStatus = get(
        url = apiUrl("broker", "status"),
        parser = PlatformJsonCodec::parseBrokerStatus
    )

    override suspend fun createDeviceEnrollment(
        request: DeviceEnrollmentRequest,
        idempotencyKey: IdempotencyKey
    ): DeviceEnrollmentCreated = executeJson(
        Request.Builder()
            .url(apiUrl("device-enrollments"))
            .standardHeaders()
            .header(IDEMPOTENCY_HEADER, idempotencyKey.value)
            .header("Cache-Control", "no-store")
            .post(jsonBody(PlatformJsonCodec.encodeDeviceEnrollmentRequest(request)))
            .build(),
        acceptedStatusCodes = setOf(202),
        parser = PlatformJsonCodec::parseDeviceEnrollmentCreated
    )

    override suspend fun getDeviceEnrollment(
        enrollmentId: UUID,
        pollSecret: SensitiveString
    ): DeviceEnrollmentState {
        val builder = Request.Builder()
            .url(apiUrl("device-enrollments", enrollmentId.toString()))
            .standardHeaders()
            .header("Cache-Control", "no-store")
        pollSecret.use { builder.header(ENROLLMENT_SECRET_HEADER, it) }
        return executeJson(
            request = builder.get().build(),
            acceptedStatusCodes = setOf(200),
            parser = PlatformJsonCodec::parseDeviceEnrollmentState
        )
    }

    override suspend fun listProducts(page: PageRequest, query: ProductQuery): Page<Product> {
        val url = pagedUrl("products", page).apply {
            query.search.addQuery(this, "q", 256)
            query.productCode.addQuery(this, "product_code", 128)
            query.productName.addQuery(this, "product_name", 256)
            query.tagId?.let(PlatformValueRules::requireTagId).addQuery(this, "tag_id", 64)
            query.source?.let { addQueryParameter("source", it.name) }
            query.isActive?.let { addQueryParameter("is_active", it.toString()) }
        }.build()
        return get(url, parser = PlatformJsonCodec::parseProductPage)
    }

    override suspend fun getProduct(productId: UUID): ProductDetail = get(
        apiUrl("products", productId.toString()),
        parser = PlatformJsonCodec::parseProductDetail
    )

    override suspend fun listBindings(page: PageRequest, query: BindingQuery): Page<Binding> {
        val url = pagedUrl("bindings", page).apply {
            query.productId?.let { addQueryParameter("product_id", it.toString()) }
            query.productCode.addQuery(this, "product_code", 128)
            query.tagId?.let(PlatformValueRules::requireTagId).addQuery(this, "tag_id", 64)
            query.stationId?.let(PlatformValueRules::requireStationId)
                .addQuery(this, "station_id", 64)
            query.source?.let { addQueryParameter("source", it.name) }
            addQueryParameter("is_active", query.isActive.toString())
        }.build()
        return get(url, parser = PlatformJsonCodec::parseBindingPage)
    }

    override suspend fun createBinding(
        request: BindingCreateRequest,
        idempotencyKey: IdempotencyKey
    ): Binding = authenticatedWrite(
        requestBuilder = Request.Builder()
            .url(apiUrl("bindings"))
            .post(jsonBody(PlatformJsonCodec.encodeBindingCreate(request))),
        idempotencyKey = idempotencyKey,
        acceptedStatusCodes = setOf(201),
        parser = PlatformJsonCodec::parseBinding
    )

    override suspend fun rebind(
        bindingId: UUID,
        request: RebindRequest,
        idempotencyKey: IdempotencyKey
    ): RebindResult = authenticatedWrite(
        requestBuilder = Request.Builder()
            .url(apiUrl("bindings", bindingId.toString(), "rebind"))
            .post(jsonBody(PlatformJsonCodec.encodeRebind(request))),
        idempotencyKey = idempotencyKey,
        acceptedStatusCodes = setOf(200),
        parser = PlatformJsonCodec::parseRebindResult
    )

    override suspend fun unbind(bindingId: UUID, idempotencyKey: IdempotencyKey): Binding =
        authenticatedWrite(
            requestBuilder = Request.Builder()
                .url(apiUrl("bindings", bindingId.toString()))
                .delete(),
            idempotencyKey = idempotencyKey,
            acceptedStatusCodes = setOf(200),
            parser = PlatformJsonCodec::parseBinding
        )

    override suspend fun previewAndroidBindingMigration(
        request: AndroidBindingMigrationPreviewRequest
    ): AndroidBindingMigrationPreview = executeJson(
        request = Request.Builder()
            .url(apiUrl("migrations", "android-bindings", "preview"))
            .standardHeaders()
            .anonymousAndroidAccess()
            .post(jsonBody(PlatformJsonCodec.encodeAndroidBindingMigrationPreview(request)))
            .build(),
        acceptedStatusCodes = setOf(200),
        parser = PlatformJsonCodec::parseAndroidBindingMigrationPreview
    )

    override suspend fun commitAndroidBindingMigration(
        request: AndroidBindingMigrationCommitRequest,
        idempotencyKey: IdempotencyKey
    ): AndroidBindingMigrationCommitResult = authenticatedWrite(
        requestBuilder = Request.Builder()
            .url(apiUrl("migrations", "android-bindings", "commit"))
            .post(jsonBody(PlatformJsonCodec.encodeAndroidBindingMigrationCommit(request))),
        idempotencyKey = idempotencyKey,
        acceptedStatusCodes = setOf(200),
        parser = PlatformJsonCodec::parseAndroidBindingMigrationCommit
    )

    override suspend fun listTags(page: PageRequest, query: TagQuery): Page<Tag> {
        val url = pagedUrl("tags", page).apply {
            query.search.addQuery(this, "q", 256)
            query.siteId?.let { addQueryParameter("site_id", it.toString()) }
            query.stationId?.let(PlatformValueRules::requireStationId)
                .addQuery(this, "station_id", 64)
            query.online?.let { addQueryParameter("online", it.toString()) }
            query.lowBattery?.let { addQueryParameter("low_battery", it.toString()) }
            query.abnormal?.let { addQueryParameter("abnormal", it.toString()) }
            query.bound?.let { addQueryParameter("bound", it.toString()) }
        }.build()
        return get(url, parser = PlatformJsonCodec::parseTagPage)
    }

    override suspend fun getTag(tagId: String): TagDetail = get(
        apiUrl("tags", PlatformValueRules.requireTagId(tagId)),
        parser = PlatformJsonCodec::parseTagDetail
    )

    override suspend fun registerTag(
        request: TagRegisterRequest,
        idempotencyKey: IdempotencyKey
    ): Tag = authenticatedWrite(
        requestBuilder = Request.Builder()
            .url(apiUrl("tags", "register"))
            .post(jsonBody(PlatformJsonCodec.encodeTagRegistration(request))),
        idempotencyKey = idempotencyKey,
        acceptedStatusCodes = setOf(201),
        parser = PlatformJsonCodec::parseTag
    )

    override suspend fun listStations(page: PageRequest, query: StationQuery): Page<Station> {
        val url = pagedUrl("stations", page).apply {
            query.search.addQuery(this, "q", 256)
            query.siteId?.let { addQueryParameter("site_id", it.toString()) }
            query.status?.let { addQueryParameter("status", it.name) }
        }.build()
        return get(url, parser = PlatformJsonCodec::parseStationPage)
    }

    override suspend fun getStation(stationId: String): Station = get(
        apiUrl("stations", PlatformValueRules.requireStationId(stationId)),
        parser = PlatformJsonCodec::parseStation
    )

    override suspend fun createLightCommand(
        request: LightCommandRequest,
        idempotencyKey: IdempotencyKey
    ): LightCommand = authenticatedWrite(
        requestBuilder = Request.Builder()
            .url(apiUrl("light-commands"))
            .post(jsonBody(PlatformJsonCodec.encodeLightCommand(request))),
        idempotencyKey = idempotencyKey,
        acceptedStatusCodes = setOf(202),
        parser = PlatformJsonCodec::parseLightCommand
    )

    override suspend fun getLightCommand(commandId: UUID): LightCommand = get(
        apiUrl("light-commands", commandId.toString()),
        parser = PlatformJsonCodec::parseLightCommand
    )

    override suspend fun allOff(stationId: String, idempotencyKey: IdempotencyKey): LightCommand =
        authenticatedWrite(
            requestBuilder = Request.Builder()
                .url(apiUrl("stations", PlatformValueRules.requireStationId(stationId), "all-off"))
                .post(jsonBody("{}")),
            idempotencyKey = idempotencyKey,
            acceptedStatusCodes = setOf(202),
            parser = PlatformJsonCodec::parseLightCommand
        )

    private suspend fun <T> get(
        url: HttpUrl,
        authenticated: Boolean = true,
        acceptedStatusCodes: Set<Int> = setOf(200),
        parseAcceptedErrorStatus: Boolean = false,
        parser: (String) -> T
    ): T {
        val builder = Request.Builder()
            .url(url)
            .standardHeaders()
            .get()
        if (authenticated) builder.anonymousAndroidAccess()
        return executeJson(
            request = builder.build(),
            acceptedStatusCodes = acceptedStatusCodes,
            parseAcceptedErrorStatus = parseAcceptedErrorStatus,
            parser = parser
        )
    }

    private suspend fun <T> authenticatedWrite(
        requestBuilder: Request.Builder,
        idempotencyKey: IdempotencyKey,
        acceptedStatusCodes: Set<Int>,
        parser: (String) -> T
    ): T {
        val request = requestBuilder
            .standardHeaders()
            .anonymousAndroidAccess()
            .header(IDEMPOTENCY_HEADER, idempotencyKey.value)
            .build()
        return executeJson(request, acceptedStatusCodes, parser = parser)
    }

    private suspend fun <T> executeJson(
        request: Request,
        acceptedStatusCodes: Set<Int>,
        parseAcceptedErrorStatus: Boolean = false,
        parser: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw PlatformTransportException(error)
        }

        response.use {
            val limit = if (response.code in acceptedStatusCodes) MAX_SUCCESS_BODY_BYTES else MAX_ERROR_BODY_BYTES
            val body = try {
                readBoundedBody(response, limit)
            } catch (error: PlatformClientException) {
                throw error
            } catch (error: IOException) {
                throw PlatformTransportException(error)
            }
            if (response.code !in acceptedStatusCodes) {
                val error = runCatching { PlatformJsonCodec.parseApiError(body) }.getOrNull()
                throw PlatformApiException(
                    statusCode = response.code,
                    apiError = error,
                    retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                )
            }
            if (!parseAcceptedErrorStatus && response.code >= 400) {
                throw PlatformApiException(response.code, null, null)
            }
            try {
                parser(body)
            } catch (error: PlatformJsonException) {
                throw PlatformProtocolException(error)
            } catch (error: IllegalArgumentException) {
                throw PlatformProtocolException(error)
            }
        }
    }

    private fun readBoundedBody(response: Response, maximumBytes: Long): String {
        val body = response.body ?: throw PlatformProtocolException()
        val declaredLength = body.contentLength()
        if (declaredLength > maximumBytes) throw PlatformProtocolException()

        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val remaining = maximumBytes + 1L - total
            if (remaining <= 0L) throw PlatformProtocolException()
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) break
            total += read
            if (total > maximumBytes) throw PlatformProtocolException()
        }
        return buffer.readString(StandardCharsets.UTF_8)
    }

    private fun apiUrl(vararg pathSegments: String): HttpUrl {
        val builder = endpointProvider.currentEndpoint().apiBaseUrl.newBuilder()
        pathSegments.forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun pagedUrl(resource: String, page: PageRequest): HttpUrl.Builder =
        endpointProvider.currentEndpoint().apiBaseUrl.newBuilder()
            .addPathSegment(resource)
            .addQueryParameter("page", page.page.toString())
            .addQueryParameter("page_size", page.pageSize.toString())
            .apply { page.sort?.let { addQueryParameter("sort", it) } }

    private fun Request.Builder.standardHeaders(): Request.Builder =
        header("Accept", JSON_MEDIA_TYPE_STRING)
            .header("Cache-Control", "no-store")
            .header("User-Agent", USER_AGENT)
            .header(INSTALLATION_ID_HEADER, tokenProvider.installationId().toString())

    private fun Request.Builder.anonymousAndroidAccess(): Request.Builder =
        removeHeader("Authorization")

    private fun String?.addQuery(builder: HttpUrl.Builder, name: String, maximumLength: Int) {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return
        require(value.length <= maximumLength) { "$name is too long." }
        builder.addQueryParameter(name, value)
    }

    private fun jsonBody(json: String) = json.toRequestBody(JSON_MEDIA_TYPE)

    companion object {
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val INSTALLATION_ID_HEADER = "X-Android-Installation-Id"
        private const val ENROLLMENT_SECRET_HEADER = "X-Enrollment-Secret"
        private const val JSON_MEDIA_TYPE_STRING = "application/json"
        private const val USER_AGENT = "HighTac-Android/1"
        private const val MAX_SUCCESS_BODY_BYTES = 8L * 1024L * 1024L
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}
