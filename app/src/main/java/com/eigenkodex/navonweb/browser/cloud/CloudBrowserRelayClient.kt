/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import android.util.Log
import com.eigenkodex.navonweb.browser.BrowserProbeServer
import com.eigenkodex.navonweb.browser.BrowserRelayRequest
import com.eigenkodex.navonweb.browser.BrowserRelayResponse
import com.eigenkodex.navonweb.session.PairingCodeLease
import java.io.Closeable
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject

private const val MAX_CLOUD_RELAY_BODY_BASE64_CHARS = 176 * 1_024

/** Normalizes one decoded WebSocket envelope into the bounded phone gateway contract. */
internal fun decodeCloudRelayRequest(
    method: String,
    target: String,
    headers: Map<String, String>,
    encodedBody: String,
): BrowserRelayRequest {
    val normalizedHeaders = linkedMapOf<String, String>()
    headers.forEach { (rawName, value) -> normalizedHeaders[rawName.lowercase()] = value }
    val body = if (encodedBody.isEmpty()) {
        ByteArray(0)
    } else {
        require(encodedBody.length <= MAX_CLOUD_RELAY_BODY_BASE64_CHARS)
        Base64.getDecoder().decode(encodedBody)
    }
    return BrowserRelayRequest(method.uppercase(), target, normalizedHeaders, body)
}

internal fun isAllowedCloudRelaySignalingRequest(request: BrowserRelayRequest): Boolean {
    val uri = runCatching { URI(request.target) }.getOrNull() ?: return false
    val path = uri.path ?: return false
    return BrowserProbeServer.isCloudSignalingRelayRequest(request.method, path)
}

internal enum class CloudPairingRegistrationResult {
    SUCCESS,
    CONFLICT,
    THROTTLED,
    EXPIRED,
    RETRY,
    ;

    companion object {
        fun fromHttpStatus(status: Int): CloudPairingRegistrationResult = when (status) {
            204 -> SUCCESS
            409 -> CONFLICT
            429 -> THROTTLED
            else -> RETRY
        }
    }
}

/** Observable readiness of the current one-time code in the cloud pairing bootstrap. */
enum class CloudPairingRegistrationStatus {
    REGISTERING,
    READY,
    RETRY,
    THROTTLED,
    EXPIRED,
}

/**
 * Identifies one persisted publication epoch without exposing its one-time code.
 *
 * Consumers must ignore an update once [CloudBrowserRelayClient.isCurrentPairingPublication]
 * returns false. This keeps delayed callbacks from an old retry job from changing a newer code.
 */
data class CloudPairingRegistrationUpdate(
    val publicationEpoch: Long,
    val status: CloudPairingRegistrationStatus,
)

internal fun CloudPairingRegistrationStatus?.canTransitionTo(
    next: CloudPairingRegistrationStatus,
): Boolean = when (this) {
    null -> true
    next -> false
    CloudPairingRegistrationStatus.READY -> next == CloudPairingRegistrationStatus.EXPIRED
    CloudPairingRegistrationStatus.EXPIRED -> false
    else -> true
}

internal data class CloudPairingRegistrationOutcome(
    val result: CloudPairingRegistrationResult,
    val retryAfterMillis: Long? = null,
) {
    companion object {
        fun fromHttp(status: Int, retryAfter: String?): CloudPairingRegistrationOutcome {
            val result = CloudPairingRegistrationResult.fromHttpStatus(status)
            return CloudPairingRegistrationOutcome(
                result = result,
                retryAfterMillis = if (result == CloudPairingRegistrationResult.THROTTLED) {
                    CloudPairingRetryAfter.delayMillis(retryAfter)
                        ?: DEFAULT_THROTTLED_RETRY_MILLIS
                } else {
                    null
                },
            )
        }
    }
}

internal object CloudPairingRetryAfter {
    private const val MAX_SECONDS = 10L * 60L
    private val DELTA_SECONDS_PATTERN = Regex("^[0-9]{1,10}$")

    fun delayMillis(value: String?): Long? {
        val normalized = value?.trim() ?: return null
        if (!DELTA_SECONDS_PATTERN.matches(normalized)) return null
        val seconds = normalized.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return seconds.coerceAtMost(MAX_SECONDS) * 1_000L
    }
}

private const val DEFAULT_THROTTLED_RETRY_MILLIS = 60_000L
private const val PAIRING_READY_REFRESH_SAFETY_MILLIS = 1_000L

internal data class CloudPairingPublication(
    val pairingCode: String,
    val epoch: Long,
    val expiresAtMonotonicMillis: Long,
) {
    init {
        require(CLOUD_PAIRING_CODE_PATTERN.matches(pairingCode)) { "invalid pairing code" }
        require(epoch in 1..MAX_SAFE_PAIRING_PUBLICATION_EPOCH) {
            "invalid pairing publication epoch"
        }
        require(expiresAtMonotonicMillis > 0L) { "invalid pairing publication deadline" }
    }

    fun remainingTtlMillis(nowMonotonicMillis: Long): Long =
        if (nowMonotonicMillis >= expiresAtMonotonicMillis) {
            0L
        } else {
            (expiresAtMonotonicMillis - nowMonotonicMillis)
                .coerceAtMost(MAX_PAIRING_PUBLICATION_TTL_MILLIS)
        }

    /**
     * Leaves one full registration-call timeout for request transit so the Worker's absolute
     * expiry cannot outlive the phone's monotonic deadline.
     */
    fun registrationTtlMillis(nowMonotonicMillis: Long): Long =
        (remainingTtlMillis(nowMonotonicMillis) - PAIRING_REGISTRATION_CALL_TIMEOUT_MILLIS)
            .coerceAtLeast(0L)

    /** Rotates before the conservative Worker slot deadline instead of showing a dead code. */
    fun readyRefreshDelayMillis(nowMonotonicMillis: Long): Long =
        (registrationTtlMillis(nowMonotonicMillis) - PAIRING_READY_REFRESH_SAFETY_MILLIS)
            .coerceAtLeast(0L)
}

/** Owns one epoch for the entire lifetime of one code publication, including reconnect retries. */
internal class CloudPairingPublicationState(
    initialPairingCode: PairingCodeLease?,
    private val nextEpoch: () -> Long,
) {
    private var current: CloudPairingPublication? = initialPairingCode
        ?.let(::newPublication)
    private var registrationComplete: CloudPairingPublication? = null
    private var registrationStatus: CloudPairingRegistrationStatus? = current
        ?.let { CloudPairingRegistrationStatus.REGISTERING }

    @Synchronized
    fun publish(pairingCode: PairingCodeLease): CloudPairingPublication {
        current?.takeIf {
            it.pairingCode == pairingCode.code &&
                it.expiresAtMonotonicMillis == pairingCode.expiresAtMonotonicMillis
        }?.let { return it }
        return newPublication(pairingCode).also {
            current = it
            registrationComplete = null
            registrationStatus = CloudPairingRegistrationStatus.REGISTERING
        }
    }

    @Synchronized
    fun current(): CloudPairingPublication? = current

    @Synchronized
    fun shouldRegister(publication: CloudPairingPublication): Boolean =
        current == publication && registrationComplete != publication

    @Synchronized
    fun markRegistrationComplete(publication: CloudPairingPublication): Boolean {
        if (current != publication) return false
        registrationComplete = publication
        return true
    }

    @Synchronized
    fun isRegistrationComplete(publication: CloudPairingPublication): Boolean =
        current == publication && registrationComplete == publication

    @Synchronized
    fun isCurrentEpoch(publicationEpoch: Long): Boolean = current?.epoch == publicationEpoch

    @Synchronized
    fun currentUpdate(): CloudPairingRegistrationUpdate? {
        val publication = current ?: return null
        val status = registrationStatus ?: CloudPairingRegistrationStatus.REGISTERING
        return CloudPairingRegistrationUpdate(publication.epoch, status)
    }

    @Synchronized
    fun transition(
        publication: CloudPairingPublication,
        status: CloudPairingRegistrationStatus,
    ): CloudPairingRegistrationUpdate? {
        if (current != publication) return null
        if (!registrationStatus.canTransitionTo(status)) return null
        registrationStatus = status
        return CloudPairingRegistrationUpdate(publication.epoch, status)
    }

    @Synchronized
    fun clear() {
        current = null
        registrationComplete = null
        registrationStatus = null
    }

    private fun newPublication(pairingCode: PairingCodeLease): CloudPairingPublication =
        CloudPairingPublication(
            pairingCode = pairingCode.code,
            epoch = nextEpoch(),
            expiresAtMonotonicMillis = pairingCode.expiresAtMonotonicMillis,
        )
}

/**
 * Keeps one outbound WSS connection to the signaling relay and executes only BrowserProbeServer's
 * explicitly bounded relay API. SDP/ICE and small control responses cross Cloudflare; WebRTC media
 * and microphone data never do.
 */
class CloudBrowserRelayClient(
    private val config: CloudBrowserRelayConfig,
    private val identity: CloudRelayIdentity,
    initialPairingCode: PairingCodeLease?,
    nextPairingPublicationEpoch: () -> Long,
    private val gateway: BrowserProbeServer,
    private val onConnectionChanged: (Boolean) -> Unit = {},
    private val onPairingRegistrationConflict: () -> Unit = {},
    private val monotonicNowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onPairingRegistrationChanged: (CloudPairingRegistrationUpdate) -> Unit = {},
    private val onPairingRegistrationConflictForPublication:
        (Long, PairingCodeLease) -> Unit = { _, _ -> },
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val inFlightMessages = AtomicInteger(0)
    private val pairingPublication = CloudPairingPublicationState(
        initialPairingCode = initialPairingCode,
        nextEpoch = nextPairingPublicationEpoch,
    )
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val socketLock = Any()
    private var socket: WebSocket? = null
    private var socketReady = false
    private var reconnectJob: Job? = null
    private var pairingRegistrationJob: Job? = null
    private var pairingRegistrationJobEpoch: Long? = null
    private var reconnectAttempt = 0

    val browserUrl: String = config.browserUrl()

    fun start() {
        if (closed.get()) return
        notifyCurrentPairingRegistration()
        synchronized(socketLock) {
            if (socket != null) return
            openSocketLocked()
        }
    }

    /** Publishes only an explicitly opened one-time code; the signed route cookie is separate. */
    fun publishPairingCode(pairingCode: PairingCodeLease) {
        val publication = pairingPublication.publish(pairingCode)
        notifyCurrentPairingRegistration()
        if (synchronized(socketLock) { socketReady }) {
            schedulePairingRegistration(publication)
        }
    }

    /**
     * Stops registration retries after pairing succeeds or its ten-minute window ends.
     * The Worker consumes a used code and expires an unused one; this never clears the browser's
     * signed route cookie or the phone's stored browser credential.
     */
    fun stopPublishingPairingCode() {
        pairingPublication.clear()
        synchronized(socketLock) {
            pairingRegistrationJob?.cancel()
            pairingRegistrationJob = null
            pairingRegistrationJobEpoch = null
        }
    }

    /** True only while [publicationEpoch] still identifies the code owned by this client. */
    fun isCurrentPairingPublication(publicationEpoch: Long): Boolean =
        pairingPublication.isCurrentEpoch(publicationEpoch)

    private fun openSocketLocked() {
        if (closed.get()) return
        socketReady = false
        val request = Request.Builder()
            .url(config.deviceWebSocketUrl(identity.roomId))
            .header("Authorization", "Bearer ${identity.deviceSecret}")
            .header("User-Agent", USER_AGENT)
            .build()
        socket = httpClient.newWebSocket(request, Listener())
    }

    private fun handleText(webSocket: WebSocket, text: String) {
        if (text.length !in 2..MAX_MESSAGE_CHARS) {
            webSocket.close(CLOSE_POLICY_VIOLATION, "message_too_large")
            return
        }
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (envelope.optString("type") != TYPE_RPC_REQUEST) return
        val requestId = envelope.optString("requestId")
        if (!REQUEST_ID_PATTERN.matches(requestId)) return
        val request = runCatching { decodeRequest(envelope) }.getOrElse {
            sendError(webSocket, requestId, 400, "invalid_relay_request")
            return
        }
        if (!isAllowedCloudRelaySignalingRequest(request)) {
            sendError(webSocket, requestId, 403, "cloud_relay_signaling_only")
            return
        }
        val response = runCatching { gateway.dispatchRelay(request) }.getOrElse {
            Log.w(LOG_TAG, "CLOUD_RELAY_REQUEST_FAILED ${it.javaClass.simpleName}")
            BrowserRelayResponse(
                status = 500,
                contentType = JSON_CONTENT_TYPE,
                body = "{\"error\":\"relay_request_failed\"}".toByteArray(),
            )
        }
        sendResponse(webSocket, requestId, response)
    }

    private fun decodeRequest(envelope: JSONObject): BrowserRelayRequest {
        val headersObject = envelope.optJSONObject("headers") ?: JSONObject()
        val headers = linkedMapOf<String, String>()
        val names = headersObject.keys()
        while (names.hasNext()) {
            val rawName = names.next()
            headers[rawName] = headersObject.getString(rawName)
        }
        return decodeCloudRelayRequest(
            method = envelope.getString("method"),
            target = envelope.getString("target"),
            headers = headers,
            encodedBody = envelope.optString("bodyBase64"),
        )
    }

    private fun sendResponse(
        webSocket: WebSocket,
        requestId: String,
        response: BrowserRelayResponse,
    ) {
        val message = JSONObject()
            .put("type", TYPE_RPC_RESPONSE)
            .put("requestId", requestId)
            .put("status", response.status)
            .put("contentType", response.contentType)
            .put(
                "bodyBase64",
                Base64.getEncoder().encodeToString(response.body),
            )
            .toString()
        if (message.length > MAX_MESSAGE_CHARS) {
            sendError(webSocket, requestId, 502, "relay_response_too_large")
        } else {
            webSocket.send(message)
        }
    }

    private fun sendError(webSocket: WebSocket, requestId: String, status: Int, error: String) {
        val body = "{\"error\":\"$error\"}".toByteArray()
        val message = JSONObject()
            .put("type", TYPE_RPC_RESPONSE)
            .put("requestId", requestId)
            .put("status", status)
            .put("contentType", JSON_CONTENT_TYPE)
            .put("bodyBase64", Base64.getEncoder().encodeToString(body))
            .toString()
        webSocket.send(message)
    }

    private fun connectionEnded(webSocket: WebSocket) {
        val shouldReconnect = synchronized(socketLock) {
            if (socket !== webSocket) {
                false
            } else {
                socket = null
                socketReady = false
                !closed.get()
            }
        }
        if (shouldReconnect) {
            onConnectionChanged(false)
            pairingPublication.current()?.let { publication ->
                notifyPairingRegistration(publication, CloudPairingRegistrationStatus.RETRY)
            }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        synchronized(socketLock) {
            if (closed.get() || reconnectJob?.isActive == true || socket != null) return
            val delayMillis = CloudRelayRetryBackoff.randomizedDelayMillis(
                attempt = reconnectAttempt.coerceAtMost(4),
                baseMillis = RECONNECT_BASE_MILLIS,
                maxMillis = RECONNECT_MAX_MILLIS,
            )
            reconnectAttempt += 1
            reconnectJob = scope.launch {
                delay(delayMillis)
                synchronized(socketLock) {
                    reconnectJob = null
                    if (!closed.get() && socket == null) openSocketLocked()
                }
            }
        }
    }

    private fun schedulePairingRegistration(publication: CloudPairingPublication) {
        if (!pairingPublication.shouldRegister(publication)) {
            notifyCurrentPairingRegistration()
            return
        }
        synchronized(socketLock) {
            if (closed.get()) return
            if (
                pairingRegistrationJob?.isActive == true &&
                pairingRegistrationJobEpoch == publication.epoch
            ) {
                return
            }
            pairingRegistrationJob?.cancel()
            pairingRegistrationJobEpoch = publication.epoch
            pairingRegistrationJob = scope.launch {
                var attempt = 0
                while (isActive && !closed.get() && pairingPublication.shouldRegister(publication)) {
                    notifyPairingRegistration(
                        publication,
                        CloudPairingRegistrationStatus.REGISTERING,
                    )
                    val outcome = registerPairingCode(publication)
                    when (outcome.result) {
                        CloudPairingRegistrationResult.SUCCESS -> {
                            if (!pairingPublication.markRegistrationComplete(publication)) {
                                return@launch
                            }
                            val becameReady = notifyPairingRegistration(
                                publication,
                                CloudPairingRegistrationStatus.READY,
                            )
                            if (
                                !becameReady &&
                                pairingPublication.currentUpdate()?.status !=
                                CloudPairingRegistrationStatus.READY
                            ) {
                                return@launch
                            }
                            val refreshDelayMillis =
                                publication.readyRefreshDelayMillis(monotonicNowMillis())
                            if (refreshDelayMillis > 0L) delay(refreshDelayMillis)
                            if (
                                isActive &&
                                !closed.get() &&
                                pairingPublication.isRegistrationComplete(publication)
                            ) {
                                notifyPairingRegistration(
                                    publication,
                                    CloudPairingRegistrationStatus.EXPIRED,
                                )
                            }
                            return@launch
                        }
                        CloudPairingRegistrationResult.CONFLICT -> {
                            if (pairingPublication.markRegistrationComplete(publication)) {
                                val conflictIsCurrent = notifyPairingRegistration(
                                    publication,
                                    CloudPairingRegistrationStatus.RETRY,
                                )
                                if (conflictIsCurrent) {
                                    runCatching(onPairingRegistrationConflict)
                                    runCatching {
                                        onPairingRegistrationConflictForPublication(
                                            publication.epoch,
                                            PairingCodeLease(
                                                code = publication.pairingCode,
                                                expiresAtMonotonicMillis =
                                                    publication.expiresAtMonotonicMillis,
                                            ),
                                        )
                                    }
                                }
                            }
                            return@launch
                        }
                        CloudPairingRegistrationResult.THROTTLED -> {
                            notifyPairingRegistration(
                                publication,
                                CloudPairingRegistrationStatus.THROTTLED,
                            )
                        }
                        CloudPairingRegistrationResult.EXPIRED -> {
                            if (pairingPublication.markRegistrationComplete(publication)) {
                                notifyPairingRegistration(
                                    publication,
                                    CloudPairingRegistrationStatus.EXPIRED,
                                )
                            }
                            return@launch
                        }
                        CloudPairingRegistrationResult.RETRY -> {
                            notifyPairingRegistration(
                                publication,
                                CloudPairingRegistrationStatus.RETRY,
                            )
                        }
                    }
                    val retryMillis = outcome.retryAfterMillis
                        ?: CloudRelayRetryBackoff.randomizedDelayMillis(
                            attempt = attempt.coerceAtMost(5),
                            baseMillis = PAIRING_REGISTER_RETRY_BASE_MILLIS,
                            maxMillis = PAIRING_REGISTER_RETRY_MAX_MILLIS,
                        )
                    val registrationTtlMillis =
                        publication.registrationTtlMillis(monotonicNowMillis())
                    if (registrationTtlMillis <= 0L) {
                        if (pairingPublication.markRegistrationComplete(publication)) {
                            notifyPairingRegistration(
                                publication,
                                CloudPairingRegistrationStatus.EXPIRED,
                            )
                        }
                        return@launch
                    }
                    attempt += 1
                    delay(retryMillis.coerceAtMost(registrationTtlMillis))
                }
            }
        }
    }

    private fun notifyCurrentPairingRegistration() {
        val update = pairingPublication.currentUpdate() ?: return
        runCatching { onPairingRegistrationChanged(update) }
    }

    private fun notifyPairingRegistration(
        publication: CloudPairingPublication,
        status: CloudPairingRegistrationStatus,
    ): Boolean {
        val update = pairingPublication.transition(publication, status) ?: return false
        runCatching { onPairingRegistrationChanged(update) }
        return true
    }

    private fun registerPairingCode(
        publication: CloudPairingPublication,
    ): CloudPairingRegistrationOutcome {
        val registrationTtlMillis = publication.registrationTtlMillis(monotonicNowMillis())
        if (registrationTtlMillis <= 0L) {
            Log.i(LOG_TAG, "CLOUD_PAIRING_ROUTE_EXPIRED_BEFORE_REGISTRATION")
            return CloudPairingRegistrationOutcome(CloudPairingRegistrationResult.EXPIRED)
        }
        val request = Request.Builder()
            .url(config.devicePairingRegistrationUrl())
            .header("Authorization", "Bearer ${identity.deviceSecret}")
            .header(PAIRING_EPOCH_HEADER, publication.epoch.toString())
            .header(PAIRING_TTL_HEADER, registrationTtlMillis.toString())
            .header("User-Agent", USER_AGENT)
            .post(publication.pairingCode.toRequestBody(TEXT_CONTENT_TYPE.toMediaType()))
            .build()
        return runCatching {
            val call = httpClient.newCall(request)
            call.timeout().timeout(PAIRING_REGISTRATION_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                CloudPairingRegistrationOutcome.fromHttp(
                    status = response.code,
                    retryAfter = response.header("Retry-After"),
                ).also { outcome ->
                    when (outcome.result) {
                        CloudPairingRegistrationResult.SUCCESS ->
                            Log.i(LOG_TAG, "CLOUD_PAIRING_ROUTE_REGISTERED")
                        CloudPairingRegistrationResult.CONFLICT ->
                            Log.w(LOG_TAG, "CLOUD_PAIRING_ROUTE_CONFLICT")
                        CloudPairingRegistrationResult.THROTTLED ->
                            Log.w(
                                LOG_TAG,
                                "CLOUD_PAIRING_ROUTE_THROTTLED " +
                                    "retryAfterMillis=${outcome.retryAfterMillis}",
                            )
                        CloudPairingRegistrationResult.EXPIRED -> Unit
                        CloudPairingRegistrationResult.RETRY ->
                            Log.w(LOG_TAG, "CLOUD_PAIRING_ROUTE_REJECTED status=${response.code}")
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "CLOUD_PAIRING_ROUTE_FAILED ${error.javaClass.simpleName}")
            CloudPairingRegistrationOutcome(CloudPairingRegistrationResult.RETRY)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val activeSocket = synchronized(socketLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            pairingRegistrationJob?.cancel()
            pairingRegistrationJob = null
            pairingRegistrationJobEpoch = null
            socketReady = false
            socket.also { socket = null }
        }
        activeSocket?.close(CLOSE_NORMAL, "service_stopped")
        scope.cancel()
        httpClient.releaseSocketsOffCallerThread(cancelInFlightCalls = false)
        onConnectionChanged(false)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val accepted = synchronized(socketLock) {
                if (closed.get() || socket !== webSocket) false else {
                    reconnectAttempt = 0
                    socketReady = true
                    true
                }
            }
            if (!accepted) {
                webSocket.close(CLOSE_NORMAL, "stale_connection")
                return
            }
            Log.i(LOG_TAG, "CLOUD_RELAY_CONNECTED")
            onConnectionChanged(true)
            pairingPublication.current()?.let(::schedulePairingRegistration)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!closed.get() && synchronized(socketLock) { socket === webSocket }) {
                val count = inFlightMessages.incrementAndGet()
                if (count > MAX_IN_FLIGHT_MESSAGES) {
                    inFlightMessages.decrementAndGet()
                    webSocket.close(CLOSE_POLICY_VIOLATION, "too_many_requests")
                    return
                }
                scope.launch {
                    try {
                        handleText(webSocket, text)
                    } finally {
                        inFlightMessages.decrementAndGet()
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            webSocket.close(CLOSE_UNSUPPORTED_DATA, "text_messages_only")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason.take(MAX_CLOSE_REASON_CHARS))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connectionEnded(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(
                LOG_TAG,
                "CLOUD_RELAY_DISCONNECTED ${t.javaClass.simpleName} " +
                    "httpStatus=${response?.code ?: 0}",
            )
            connectionEnded(webSocket)
        }
    }

    private companion object {
        const val LOG_TAG = "NavOnWebCloudRelay"
        const val USER_AGENT = "NavOnWeb-Android/1"
        const val PAIRING_EPOCH_HEADER = "X-NavOnWeb-Pairing-Epoch"
        const val PAIRING_TTL_HEADER = "X-NavOnWeb-Pairing-Ttl-Millis"
        const val TYPE_RPC_REQUEST = "rpc_request"
        const val TYPE_RPC_RESPONSE = "rpc_response"
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val PING_INTERVAL_SECONDS = 25L
        const val RECONNECT_BASE_MILLIS = 1_000L
        const val RECONNECT_MAX_MILLIS = 15_000L
        const val PAIRING_REGISTER_RETRY_BASE_MILLIS = 1_000L
        const val PAIRING_REGISTER_RETRY_MAX_MILLIS = 30_000L
        const val MAX_MESSAGE_CHARS = 192 * 1_024
        const val MAX_CLOSE_REASON_CHARS = 100
        const val MAX_IN_FLIGHT_MESSAGES = 16
        const val CLOSE_NORMAL = 1000
        const val CLOSE_UNSUPPORTED_DATA = 1003
        const val CLOSE_POLICY_VIOLATION = 1008
        val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,64}$")
    }
}

private val CLOUD_PAIRING_CODE_PATTERN = Regex("^\\d{8}$")
private const val MAX_PAIRING_PUBLICATION_TTL_MILLIS = 10L * 60L * 1_000L
internal const val PAIRING_REGISTRATION_CALL_TIMEOUT_MILLIS = 20_000L
