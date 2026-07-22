package com.example.deepchatdemo.platform.network

import com.example.deepchatdemo.platform.config.PlatformEndpointProvider
import com.example.deepchatdemo.platform.json.PlatformJsonCodec
import com.example.deepchatdemo.platform.model.PlatformEvent
import com.example.deepchatdemo.platform.security.PlatformAuthTokenProvider
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed interface PlatformEventConnectionState {
    data object Stopped : PlatformEventConnectionState

    data class Connecting(val attempt: Int) : PlatformEventConnectionState

    data class Connected(val connectedAt: Instant) : PlatformEventConnectionState

    data class ReconnectScheduled(
        val attempt: Int,
        val delayMillis: Long
    ) : PlatformEventConnectionState

    data object AuthenticationRequired : PlatformEventConnectionState
}

enum class PlatformEventFailureKind {
    TRANSPORT,
    AUTHENTICATION_REJECTED,
    MALFORMED_EVENT,
    BINARY_MESSAGE_NOT_SUPPORTED,
    EVENT_BUFFER_FULL
}

data class PlatformEventFailure(
    val kind: PlatformEventFailureKind,
    val httpStatusCode: Int? = null
)

enum class SnapshotRefreshReason {
    RECONNECTED,
    EVENT_BUFFER_FULL
}

data class SnapshotRefreshRequired(
    val generation: Long,
    val reason: SnapshotRefreshReason,
    val signaledAt: Instant
)

interface PlatformEventStream {
    val connectionState: StateFlow<PlatformEventConnectionState>
    val events: SharedFlow<PlatformEvent>
    val failures: SharedFlow<PlatformEventFailure>
    val snapshotRefreshGeneration: StateFlow<Long>
    val snapshotRefreshSignals: SharedFlow<SnapshotRefreshRequired>

    fun connect()

    fun disconnect()
}

class OkHttpPlatformEventClient(
    private val endpointProvider: PlatformEndpointProvider,
    private val tokenProvider: PlatformAuthTokenProvider,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val client: OkHttpClient = defaultWebSocketClient(),
    private val now: () -> Instant = Instant::now
) : PlatformEventStream {
    private val lock = Any()
    private val _connectionState = MutableStateFlow<PlatformEventConnectionState>(
        PlatformEventConnectionState.Stopped
    )
    private val _events = MutableSharedFlow<PlatformEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val _failures = MutableSharedFlow<PlatformEventFailure>(extraBufferCapacity = 16)
    private val _snapshotRefreshGeneration = MutableStateFlow(0L)
    private val _snapshotRefreshSignals = MutableSharedFlow<SnapshotRefreshRequired>(
        extraBufferCapacity = 8
    )

    override val connectionState: StateFlow<PlatformEventConnectionState> =
        _connectionState.asStateFlow()
    override val events: SharedFlow<PlatformEvent> = _events.asSharedFlow()
    override val failures: SharedFlow<PlatformEventFailure> = _failures.asSharedFlow()
    override val snapshotRefreshGeneration: StateFlow<Long> =
        _snapshotRefreshGeneration.asStateFlow()
    override val snapshotRefreshSignals: SharedFlow<SnapshotRefreshRequired> =
        _snapshotRefreshSignals.asSharedFlow()

    private var shouldRun = false
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var callbackGeneration = 0L
    private var reconnectAttempt = 0
    private var hasConnected = false
    private var disconnectedAfterConnection = false

    override fun connect() {
        synchronized(lock) {
            if (shouldRun) return
            shouldRun = true
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
            openSocketLocked()
        }
    }

    override fun disconnect() {
        val socket = synchronized(lock) {
            shouldRun = false
            reconnectJob?.cancel()
            reconnectJob = null
            callbackGeneration += 1
            if (hasConnected) disconnectedAfterConnection = true
            val current = webSocket
            webSocket = null
            _connectionState.value = PlatformEventConnectionState.Stopped
            current
        }
        socket?.close(NORMAL_CLOSURE_CODE, null)
    }

    private fun openSocketLocked() {
        if (!shouldRun) return
        callbackGeneration += 1
        val generation = callbackGeneration
        val displayedAttempt = reconnectAttempt + 1
        _connectionState.value = PlatformEventConnectionState.Connecting(displayedAttempt)
        val builder = Request.Builder()
            .url(endpointProvider.currentEndpoint().eventsWebSocketUrl)
            .header("User-Agent", USER_AGENT)
            .header(INSTALLATION_ID_HEADER, tokenProvider.installationId().toString())
            .removeHeader("Authorization")
        webSocket = client.newWebSocket(builder.build(), Listener(generation))
    }

    private inner class Listener(
        private val generation: Long
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val requiresRefresh = synchronized(lock) {
                if (!isCurrentLocked(generation) || !shouldRun) {
                    webSocket.close(NORMAL_CLOSURE_CODE, null)
                    return
                }
                this@OkHttpPlatformEventClient.webSocket = webSocket
                val reconnect = disconnectedAfterConnection || reconnectAttempt > 0
                hasConnected = true
                disconnectedAfterConnection = false
                reconnectAttempt = 0
                reconnectJob = null
                _connectionState.value = PlatformEventConnectionState.Connected(now())
                reconnect
            }
            if (requiresRefresh) signalSnapshotRefresh(SnapshotRefreshReason.RECONNECTED)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(generation)) return
            val event = try {
                PlatformJsonCodec.parseEvent(text)
            } catch (_: IllegalArgumentException) {
                _failures.tryEmit(PlatformEventFailure(PlatformEventFailureKind.MALFORMED_EVENT))
                return
            }
            if (!_events.tryEmit(event)) {
                _failures.tryEmit(PlatformEventFailure(PlatformEventFailureKind.EVENT_BUFFER_FULL))
                signalSnapshotRefresh(SnapshotRefreshReason.EVENT_BUFFER_FULL)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(generation)) return
            _failures.tryEmit(
                PlatformEventFailure(PlatformEventFailureKind.BINARY_MESSAGE_NOT_SUPPORTED)
            )
            webSocket.close(UNSUPPORTED_DATA_CODE, null)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(
                generation = generation,
                authenticationRejected = code == POLICY_VIOLATION_CODE,
                httpStatusCode = null
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val statusCode = response?.code
            response?.close()
            handleDisconnect(
                generation = generation,
                authenticationRejected = statusCode == 401 || statusCode == 403,
                httpStatusCode = statusCode
            )
        }
    }

    private fun handleDisconnect(
        generation: Long,
        authenticationRejected: Boolean,
        httpStatusCode: Int?
    ) {
        synchronized(lock) {
            if (!isCurrentLocked(generation)) return
            webSocket = null
            if (hasConnected) disconnectedAfterConnection = true
            if (!shouldRun) {
                _connectionState.value = PlatformEventConnectionState.Stopped
                return
            }
            if (authenticationRejected) {
                shouldRun = false
                _connectionState.value = PlatformEventConnectionState.AuthenticationRequired
                _failures.tryEmit(
                    PlatformEventFailure(
                        kind = PlatformEventFailureKind.AUTHENTICATION_REJECTED,
                        httpStatusCode = httpStatusCode
                    )
                )
                return
            }
            _failures.tryEmit(
                PlatformEventFailure(
                    kind = PlatformEventFailureKind.TRANSPORT,
                    httpStatusCode = httpStatusCode
                )
            )
            scheduleReconnectLocked()
        }
    }

    private fun scheduleReconnectLocked() {
        reconnectJob?.cancel()
        val policyAttempt = reconnectAttempt
        reconnectAttempt += 1
        val delayMillis = reconnectPolicy.delayMillis(policyAttempt)
        _connectionState.value = PlatformEventConnectionState.ReconnectScheduled(
            attempt = reconnectAttempt,
            delayMillis = delayMillis
        )
        val expectedGeneration = callbackGeneration
        reconnectJob = scope.launch {
            delay(delayMillis)
            synchronized(lock) {
                if (!shouldRun || callbackGeneration != expectedGeneration) return@synchronized
                openSocketLocked()
            }
        }
    }

    private fun signalSnapshotRefresh(reason: SnapshotRefreshReason) {
        val signal = synchronized(lock) {
            val generation = _snapshotRefreshGeneration.value + 1L
            _snapshotRefreshGeneration.value = generation
            SnapshotRefreshRequired(generation, reason, now())
        }
        _snapshotRefreshSignals.tryEmit(signal)
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        isCurrentLocked(generation)
    }

    private fun isCurrentLocked(generation: Long): Boolean = callbackGeneration == generation

    companion object {
        private const val USER_AGENT = "HighTac-Android/1"
        private const val INSTALLATION_ID_HEADER = "X-Android-Installation-Id"
        private const val EVENT_BUFFER_CAPACITY = 128
        private const val NORMAL_CLOSURE_CODE = 1_000
        private const val UNSUPPORTED_DATA_CODE = 1_003
        private const val POLICY_VIOLATION_CODE = 1_008

        fun defaultWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}
