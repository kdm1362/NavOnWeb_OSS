/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.eigenkodex.navonweb.BuildConfig
import com.eigenkodex.navonweb.audio.Pcm16Format
import com.eigenkodex.navonweb.billing.PremiumAccessGate
import com.eigenkodex.navonweb.browser.audio.BrowserAudioStreamHub
import com.eigenkodex.navonweb.browser.audio.BrowserAudioSubscription
import com.eigenkodex.navonweb.browser.audio.BrowserAudioTrack
import com.eigenkodex.navonweb.browser.audio.BrowserMicrophonePcmSource
import com.eigenkodex.navonweb.core.NormalizedTouch
import com.eigenkodex.navonweb.core.TouchPhase
import com.eigenkodex.navonweb.openauto.EngineSnapshot
import com.eigenkodex.navonweb.openauto.FreeProjectionProfileControl
import com.eigenkodex.navonweb.openauto.FreeProjectionViewportControl
import com.eigenkodex.navonweb.openauto.ProjectionProfileControl
import com.eigenkodex.navonweb.openauto.ProjectionProfileSnapshot
import com.eigenkodex.navonweb.openauto.ProjectionViewportControl
import com.eigenkodex.navonweb.openauto.ProjectionViewportResolver
import com.eigenkodex.navonweb.openauto.ProjectionViewportSnapshot
import com.eigenkodex.navonweb.openauto.OpenAutoKeyEvent
import com.eigenkodex.navonweb.session.BrowserTrustStore
import com.eigenkodex.navonweb.session.AuthenticatedBrowserDevice
import com.eigenkodex.navonweb.session.BrowserCredentialIssueResult
import com.eigenkodex.navonweb.session.BrowserDevicePermission
import com.eigenkodex.navonweb.session.PairedBrowserMutationResult
import com.eigenkodex.navonweb.session.AndroidAutoConnectionStatus
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.PairingCodeDecision
import com.eigenkodex.navonweb.session.PairingCodeGate
import com.eigenkodex.navonweb.session.PairingCodeLease
import com.eigenkodex.navonweb.session.PairingCodePublicationState
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.zip.GZIPOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun shouldRotateAfterBootstrapConflict(
    currentPublication: PairingCodeLease?,
    expectedPublication: PairingCodeLease?,
): Boolean = currentPublication != null &&
    (expectedPublication == null || currentPublication == expectedPublication)

/** Shared local/cloud pairing issuance boundary after the one-time code is accepted. */
internal fun issuePairedBrowserDeviceFromPairingHeaders(
    browserTrustStore: BrowserTrustStore,
    headers: Map<String, String>,
): BrowserCredentialIssueResult = browserTrustStore.pairNewDevice(
    displayName = headers["x-browser-device-name"],
    permission = BrowserDevicePermission.CONTROL,
)

class BrowserProbeServer(
    private val context: Context,
    private val pairingCode: String,
    private val browserTrustStore: BrowserTrustStore,
    private val frameStore: BrowserFrameStore,
    private val snapshot: () -> EngineSnapshot,
    private val androidAutoConnection: () -> AndroidAutoConnectionStatus,
    private val touchInputReady: () -> Boolean = { false },
    private val onTouch: (NormalizedTouch) -> Boolean,
    private val keyInputReady: () -> Boolean = touchInputReady,
    private val onKey: (OpenAutoKeyEvent) -> Boolean = { false },
    private val webRtcSignaling: BrowserWebRtcSignaling = BrowserWebRtcSignaling.Unavailable,
    /** Added only to capabilities served through [dispatchRelay], never the local HTTP API. */
    private val cloudLanStunIceServer: () -> BrowserWebRtcIceServer? = { null },
    private val projectionProfiles: ProjectionProfileControl = FreeProjectionProfileControl,
    private val projectionViewport: ProjectionViewportControl = FreeProjectionViewportControl,
    /** Trusted phone-app preference. Browser query values are validation-only. */
    private val preferredWebRtcCodec: () -> BrowserWebRtcCodec = { BrowserWebRtcCodec.AUTO },
    private val audioStreams: BrowserAudioStreamHub? = null,
    private val microphoneSource: BrowserMicrophonePcmSource? = null,
    private val noticeFeed: () -> BrowserNoticeFeed = { BrowserNoticeFeed.UNAVAILABLE },
    /** A null value closes the public pairing window without revoking trusted browsers. */
    private val onPairingCodePublicationChanged: (PairingCodeLease?) -> Unit = {},
    private val browserSessionLimitProvider: () -> Int = {
        browserWebRtcSessionLimit(PremiumAccessGate.isPremium(context.applicationContext))
    },
    private val pairingCodeOperationLock: Any = Any(),
) : Closeable {
    private data class CoherentProjectionSnapshot(
        val profile: ProjectionProfileSnapshot,
        val viewport: ProjectionViewportSnapshot,
    )

    // A dedicated parallelism view keeps up to MAX_CLIENTS blocking socket reads (plus the
    // accept, watchdog, and pairing-rotation loops) from consuming the shared Dispatchers.IO
    // budget the AASDK transport and the rest of the app depend on; Dispatchers.IO grants
    // limitedParallelism views additional threads beyond its default cap on demand.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CLIENTS + 8),
    )
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val pairingCodeGate = PairingCodeGate(pairingCode)
    private val pairingCodePublication = PairingCodePublicationState(
        initialCode = pairingCode,
        hasStoredBrowserCredential = browserTrustStore.hasStoredCredential(),
    )
    private val viewportControllerGate = BrowserViewportControllerGate(SystemClock::elapsedRealtime)
    private val clientSlots = Semaphore(MAX_CLIENTS, true)
    private val frameClientSlots = Semaphore(MAX_FRAME_CLIENTS, true)
    private val audioClientSlots = Semaphore(MAX_AUDIO_CLIENTS, true)
    private val activeClientSockets = ActiveClientSocketRegistry()
    private val touchPresenceSequence = AtomicLong(0L)
    private val touchGestureGate = BrowserTouchGestureGate(SystemClock::elapsedRealtime)
    private val deviceSeenLock = Any()
    private val lastDeviceSeenRecordMillis = mutableMapOf<String, Long>()

    fun start(port: Int = DEFAULT_PORT): Int {
        check(serverSocket == null) { "Server already started" }
        check(!activeClientSockets.isClosed()) { "Server is closed" }

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", port), MAX_ACCEPT_BACKLOG)
        serverSocket = socket

        acceptJob = scope.launch {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (!clientSlots.tryAcquire()) {
                    client.close()
                    continue
                }
                if (!activeClientSockets.register(client)) {
                    clientSlots.release()
                    break
                }
                launch {
                    try {
                        handleClient(client)
                    } finally {
                        activeClientSockets.release(client)
                        clientSlots.release()
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(PAIRING_CODE_REFRESH_INTERVAL_MILLIS)
                synchronized(pairingCodeOperationLock) {
                    if (pairingCodePublication.currentCode() != null) {
                        pairingCodeGate.rotateIfExpired()?.let { replacementCode ->
                            publishPairingCode(
                                pairingCodePublication.afterRotation(
                                    code = replacementCode,
                                    keepOpen = false,
                                ),
                            )
                        }
                    }
                }
            }
        }
        return socket.localPort
    }

    fun currentPairingCode(): String = pairingCodeGate.currentCode()

    /** The code currently advertised to browsers, or null when pairing is closed. */
    fun publishedPairingCode(): String? = synchronized(pairingCodeOperationLock) {
        pairingCodePublication.currentCode()
    }

    /** The advertised code with the exact deadline owned by PairingCodeGate. */
    fun publishedPairingCodeLease(): PairingCodeLease? = synchronized(pairingCodeOperationLock) {
        publishedPairingCodeLeaseLocked()
    }

    /**
     * Opens or force-refreshes a ten-minute pairing window. Repeated requests inside the
     * ten-second cooldown are idempotent so rapid UI taps cannot amplify Cloudflare writes.
     */
    fun requestNewBrowserPairing(): String = synchronized(pairingCodeOperationLock) {
        pairingCodePublication
            .request(pairingCodeGate::rotateForNewPairingRequest)
            .also { request ->
                if (request.shouldPublish) publishPairingCode(request.code)
            }
            .code
    }

    fun pairedBrowserDevices(): List<com.eigenkodex.navonweb.session.PairedBrowserDevice> {
        val liveColors = webRtcSignaling.connectedSessionMetadata()
            .associate { it.deviceId to it.colorSlot }
        return browserTrustStore.devices().map { device ->
            liveColors[device.deviceId]?.let { device.copy(colorSlot = it) } ?: device
        }
    }

    fun connectedBrowserDeviceIds(): Set<String> = webRtcSignaling.connectedDeviceIds()

    fun renamePairedBrowserDevice(
        deviceId: String,
        displayName: String,
    ): PairedBrowserMutationResult = synchronized(pairingCodeOperationLock) {
        browserTrustStore.renameDevice(deviceId, displayName).also { result ->
            if (result is PairedBrowserMutationResult.Updated) {
                result.device?.let { renamed ->
                    webRtcSignaling.updateDeviceDisplayName(deviceId, renamed.displayName)
                }
            }
        }
    }

    fun updatePairedBrowserAccess(
        deviceId: String,
        permission: BrowserDevicePermission,
    ): PairedBrowserMutationResult = synchronized(pairingCodeOperationLock) {
        val previous = browserTrustStore.devices()
            .firstOrNull { it.deviceId == deviceId }
            ?.permission
        browserTrustStore.updateAccess(deviceId, permission).also { result ->
            if (result is PairedBrowserMutationResult.Updated && previous != permission) {
                webRtcSignaling.closeSessionsForDevice(deviceId)
                ensurePreferredMainFromLiveSessionLocked()
            }
        }
    }

    fun setPreferredMainBrowserDevice(deviceId: String?): PairedBrowserMutationResult =
        synchronized(pairingCodeOperationLock) {
            val result = browserTrustStore.setPreferredMain(deviceId)
            if (result is PairedBrowserMutationResult.Updated && deviceId == null) {
                ensurePreferredMainFromLiveSessionLocked() ?: result
            } else {
                result
            }
        }

    fun removePairedBrowserDevice(
        deviceId: String,
    ): com.eigenkodex.navonweb.session.PairedBrowserRemovalResult =
        synchronized(pairingCodeOperationLock) {
            browserTrustStore.remove(deviceId).also { result ->
                if (result is com.eigenkodex.navonweb.session.PairedBrowserRemovalResult.Removed) {
                    webRtcSignaling.closeSessionsForOwner(result.ownerKey)
                    ensurePreferredMainFromLiveSessionLocked()
                }
            }
        }

    private fun ensurePreferredMainFromLiveSessionLocked(): PairedBrowserMutationResult? {
        if (browserTrustStore.preferredMainDeviceId() != null) return null
        val candidate = webRtcSignaling.oldestInteractiveDeviceId() ?: return null
        return browserTrustStore.setPreferredMainIfUnset(candidate)
    }

    fun rotatePairingCodeAfterBootstrapConflict(
        expectedPublication: PairingCodeLease? = null,
    ): String? {
        val replacement = synchronized(pairingCodeOperationLock) {
            val currentPublication = publishedPairingCodeLeaseLocked()
            if (!shouldRotateAfterBootstrapConflict(currentPublication, expectedPublication)) {
                return@synchronized null
            }
            pairingCodeGate.rotateAfterBootstrapConflict().also { replacementCode ->
                pairingCodePublication.afterRotation(replacementCode, keepOpen = true)
            }
        }
        replacement?.let(::publishPairingCode)
        return replacement
    }

    /**
     * Executes only pairing, connection metadata, and WebRTC signaling over Cloudflare.
     *
     * Touch, notices, frame, microphone, and output-audio requests are deliberately absent.
     * Projection viewport changes are allowed only because the browser must apply its initial
     * portrait/aspect geometry before the first WebRTC offer. After DataChannel cutover the browser
     * routes them through [dispatchWebRtcControl].
     */
    fun dispatchRelay(request: BrowserRelayRequest): BrowserRelayResponse {
        val parsed = HttpRequest.fromRelay(request)
            ?: return BrowserRelayResponse(400, JSON_CONTENT_TYPE, ERROR_INVALID_RELAY_REQUEST)
        if (!isCloudSignalingRelayRequest(parsed.method, parsed.path)) {
            return BrowserRelayResponse(403, JSON_CONTENT_TYPE, ERROR_CLOUD_SIGNALING_ONLY)
        }
        val output = ByteArrayOutputStream()
        when {
            parsed.method == "GET" && parsed.path == "/health" ->
                writeResponse(output, 200, "text/plain; charset=utf-8", "ok".toByteArray())
            parsed.method == "POST" && parsed.path == "/api/pair" ->
                servePair(parsed, output)
            !isAuthorized(parsed) ->
                writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            parsed.method == "GET" && parsed.path == "/api/status" ->
                serveStatus(parsed, output)
            parsed.method == "GET" && parsed.path == "/api/projection/profile" ->
                serveProjectionProfile(output)
            parsed.method == "GET" && parsed.path == "/api/projection/viewport" ->
                serveProjectionViewport(output)
            parsed.method == "POST" && parsed.path == "/api/projection/viewport" ->
                serveProjectionViewportRequest(parsed, output)
            parsed.method == "GET" && parsed.path == "/api/webrtc/capabilities" ->
                serveWebRtcCapabilities(output, includeCloudLanStun = true)
            parsed.method == "POST" && parsed.path == "/api/webrtc/session" ->
                serveWebRtcOpen(parsed, directPeerIpv4 = null, output = output)
            isWebRtcSessionPath(parsed.path) && parsed.method == "GET" ->
                serveWebRtcSession(parsed, output)
            isWebRtcSessionPath(parsed.path) && parsed.method == "DELETE" ->
                serveWebRtcClose(parsed, output)
            else -> writeResponse(output, 403, JSON_CONTENT_TYPE, ERROR_CLOUD_SIGNALING_ONLY)
        }
        return decodeRelayResponse(output)
    }

    /** Executes post-connect browser control exclusively on the local WebRTC DataChannel. */
    fun dispatchWebRtcControl(request: BrowserRelayRequest): BrowserRelayResponse =
        dispatchWebRtcControl(expectedOwnerKey = null, request = request)

    /**
     * The DataChannel owner is authenticated again against the credential inside each RPC. This
     * prevents one trusted device from copying its credential into another device's live channel.
     */
    fun dispatchWebRtcControl(
        expectedOwnerKey: String?,
        request: BrowserRelayRequest,
    ): BrowserRelayResponse {
        val parsed = HttpRequest.fromRelay(request)
            ?: return BrowserRelayResponse(400, JSON_CONTENT_TYPE, ERROR_INVALID_RELAY_REQUEST)
        val output = ByteArrayOutputStream()
        val authenticated = authenticatedDevice(parsed)
        when {
            authenticated == null ||
                (expectedOwnerKey != null && authenticated.ownerKey != expectedOwnerKey) ->
                writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            parsed.method == "GET" && parsed.path == "/api/status" ->
                serveStatus(parsed, output, authenticated)
            parsed.method == "GET" && parsed.path == "/api/notices" ->
                serveNotices(output)
            parsed.method == "GET" && parsed.path == "/api/projection/profile" ->
                serveProjectionProfile(output)
            parsed.method == "GET" && parsed.path == "/api/projection/viewport" ->
                serveProjectionViewport(output)
            parsed.method == "POST" && parsed.path == "/api/projection/viewport" ->
                serveProjectionViewportRequest(parsed, output)
            parsed.method == "POST" && parsed.path == "/api/touch" ->
                serveTouch(parsed, output)
            parsed.method == "POST" && parsed.path == "/api/key" ->
                serveKey(parsed, output)
            else -> writeResponse(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
        }
        return decodeRelayResponse(output)
    }

    private fun decodeRelayResponse(output: ByteArrayOutputStream): BrowserRelayResponse {
        return BrowserRelayResponse.decodeHttpResponse(output.toByteArray())
            ?: BrowserRelayResponse(500, JSON_CONTENT_TYPE, ERROR_INVALID_RELAY_RESPONSE)
    }

    private fun handleClient(client: Socket) {
        runCatching {
            client.use { socket ->
                socket.soTimeout = REQUEST_SOCKET_TIMEOUT_MILLIS
                // Touch acknowledgements and poll responses are tiny; keep them out of Nagle
                // buffers so they are not delayed behind the peer's delayed ACK timer.
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = HttpConnectionOutput(socket.getOutputStream())
                var served = 0
                while (served < MAX_REQUESTS_PER_CONNECTION) {
                    val request = HttpRequest.read(input)
                    if (request == null) {
                        // Only a fresh connection's malformed first request earns a reply; a
                        // kept-alive connection that goes idle or closes simply ends.
                        if (served == 0) {
                            writeResponse(output, 400, "text/plain; charset=utf-8", "Bad request".toByteArray())
                        }
                        return@use
                    }
                    served += 1
                    // Chunked audio owns its socket until disconnect. Every other endpoint may
                    // reuse the connection, sparing the browser a TCP handshake per touch event
                    // and per status/frame poll. Relay and DataChannel dispatch never enter this
                    // loop, so the Cloudflare-hosted page's signaling behavior is unchanged.
                    output.keepAlive = request.keepAliveCapable &&
                        !(request.method == "GET" && audioStreamTrack(request.path) != null) &&
                        served < MAX_REQUESTS_PER_CONNECTION

                    when {
                        request.method == "GET" && request.path == "/" -> serveIndex(request, output)
                        request.method == "GET" && request.path == "/app.js" -> serveAppScript(request, output)
                        request.method == "GET" && request.path == "/health" -> {
                            writeResponse(output, 200, "text/plain; charset=utf-8", "ok".toByteArray())
                        }
                        request.method == "GET" && request.path == "/favicon.ico" -> {
                            writeResponse(output, 204, "image/x-icon", ByteArray(0))
                        }
                        request.method == "POST" && request.path == "/api/pair" -> {
                            servePair(request, output)
                        }
                        !isAuthorized(request) -> {
                            writeResponse(output, 401, "application/json", "{\"error\":\"invalid_browser_credential\"}".toByteArray())
                        }
                        request.method == "GET" && request.path == "/api/status" -> {
                            serveStatus(request, output)
                        }
                        request.method == "GET" && request.path == "/api/notices" -> {
                            serveNotices(output)
                        }
                        request.method == "GET" && audioStreamTrack(request.path) != null -> {
                            serveAudio(request, socket)
                        }
                        request.method == "POST" && request.path == "/api/microphone" -> {
                            serveMicrophone(request, socket.inetAddress, output)
                        }
                        request.method == "GET" && request.path == "/api/projection/profile" -> {
                            serveProjectionProfile(output)
                        }
                        request.method == "POST" && request.path == "/api/projection/profile" -> {
                            writeResponse(
                                output,
                                405,
                                "application/json; charset=utf-8",
                                "{\"error\":\"profile_changes_app_only\"}".toByteArray(),
                            )
                        }
                        request.method == "GET" && request.path == "/api/projection/viewport" -> {
                            serveProjectionViewport(output)
                        }
                        request.method == "POST" && request.path == "/api/projection/viewport" -> {
                            serveProjectionViewportRequest(request, output)
                        }
                        request.method == "GET" && request.path == "/api/webrtc/capabilities" -> {
                            serveWebRtcCapabilities(
                                output,
                                includeCloudLanStun = false,
                            )
                        }
                        request.method == "POST" && request.path == "/api/webrtc/session" -> {
                            serveWebRtcOpen(
                                request = request,
                                directPeerIpv4 = eligibleDirectPeerIpv4(socket.inetAddress as? Inet4Address),
                                output = output,
                            )
                        }
                        isWebRtcSessionPath(request.path) && request.method == "GET" -> {
                            serveWebRtcSession(request, output)
                        }
                        isWebRtcSessionPath(request.path) && request.method == "DELETE" -> {
                            serveWebRtcClose(request, output)
                        }
                        request.method == "GET" && request.path == "/api/frame.jpg" -> {
                            serveFrame(request, output)
                        }
                        request.method == "POST" && request.path == "/api/touch" -> serveTouch(request, output)
                        else -> writeResponse(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
                    }
                    if (!output.keepAlive) return@use
                }
            }
        }
    }

    /**
     * Socket-path response stream carrying the per-request persistence decision to the shared
     * response writers. Relay and DataChannel dispatch write into plain byte sinks, so their
     * serialized responses keep the historical Connection: close form byte-for-byte.
     */
    private class HttpConnectionOutput(delegate: OutputStream) : FilterOutputStream(delegate) {
        @Volatile
        var keepAlive: Boolean = false

        // FilterOutputStream degrades block writes to byte-at-a-time; delegate directly.
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
        }
    }

    private class CachedStaticAsset(val identity: ByteArray, val gzip: ByteArray)

    private val staticAssetLock = Any()
    private var cachedIndexAsset: CachedStaticAsset? = null
    private var cachedAppScriptAsset: CachedStaticAsset? = null

    private fun gzipBytes(body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(body.size / 3 + 64)
        GZIPOutputStream(output).use { stream -> stream.write(body) }
        return output.toByteArray()
    }

    /**
     * The packaged page and script are immutable for the process lifetime (the only template
     * substitution depends on BuildConfig.DEBUG), so both encodings are rendered once instead
     * of re-reading and re-templating ~300 KB of assets on every page load.
     */
    private fun indexAsset(): CachedStaticAsset = synchronized(staticAssetLock) {
        cachedIndexAsset ?: run {
            val body = context.assets.open("tesla/index.html").use { it.readBytes() }
            CachedStaticAsset(body, gzipBytes(body)).also { cachedIndexAsset = it }
        }
    }

    private fun appScriptAsset(): CachedStaticAsset = synchronized(staticAssetLock) {
        cachedAppScriptAsset ?: run {
            val template = context.assets.open("tesla/app.js").use { input ->
                String(input.readBytes(), StandardCharsets.UTF_8)
            }
            val body = renderAppScript(template, isDebugBuild = BuildConfig.DEBUG)
                .toByteArray(StandardCharsets.UTF_8)
            CachedStaticAsset(body, gzipBytes(body)).also { cachedAppScriptAsset = it }
        }
    }

    private fun serveStaticAsset(
        request: HttpRequest,
        output: OutputStream,
        contentType: String,
        asset: CachedStaticAsset,
    ) {
        if (acceptEncodingAllowsGzip(request.headers["accept-encoding"])) {
            writeResponse(
                output,
                200,
                contentType,
                asset.gzip,
                extraHeaders = mapOf("Content-Encoding" to "gzip", "Vary" to "Accept-Encoding"),
            )
        } else {
            writeResponse(
                output,
                200,
                contentType,
                asset.identity,
                extraHeaders = mapOf("Vary" to "Accept-Encoding"),
            )
        }
    }

    private fun serveIndex(request: HttpRequest, output: OutputStream) {
        serveStaticAsset(request, output, "text/html; charset=utf-8", indexAsset())
    }

    private fun serveAppScript(request: HttpRequest, output: OutputStream) {
        serveStaticAsset(request, output, "application/javascript; charset=utf-8", appScriptAsset())
    }

    private fun serveStatus(
        request: HttpRequest,
        output: OutputStream,
        authenticated: AuthenticatedBrowserDevice? = authenticatedDevice(request),
    ) {
        val device = authenticated ?: run {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        reconcileBrowserSessionPolicy(
            sessionLimitProvider = browserSessionLimitProvider,
            preferredMainDeviceId = browserTrustStore::preferredMainDeviceId,
            reconcile = webRtcSignaling::reconcileSessionLimit,
        )
        refreshViewportController(request)
        recordDeviceSeen(device)
        val projection = coherentProjectionSnapshot() ?: run {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"projection_reconfiguring\"}".toByteArray(),
            )
            return
        }
        val current = snapshot()
        val currentFrame = frameStore.latest()
        val connection = androidAutoConnection()
        val nativeFrameAgeMillis = connection.lastFrameAgeMillis(SystemClock.elapsedRealtime())
        val body = buildString {
            append("{\"state\":\"")
            append(jsonEscape(current.state.name))
            append("\",\"detail\":\"")
            append(jsonEscape(current.detail))
            append("\",\"nativeRuntimeLinked\":")
            append(current.nativeRuntimeLinked)
            append(",\"frameAvailable\":")
            append(currentFrame != null)
            append(",\"frameVersion\":")
            append(currentFrame?.version ?: 0L)
            append(",\"frameAgeMillis\":")
            append(
                currentFrame?.let {
                    ((System.nanoTime() - it.capturedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
                } ?: 0L,
            )
            append(",\"androidAuto\":{\"state\":")
            appendJsonString(connection.state.name)
            append(",\"reason\":")
            appendJsonString(connection.reason.wireName)
            append(",\"message\":")
            appendJsonString(context.getString(connection.reason.messageRes))
            append(",\"lastFrameAgeMillis\":")
            append(nativeFrameAgeMillis ?: "null")
            append(",\"lastFrameAtEpochMillis\":")
            append(connection.lastFrameAtEpochMillis ?: "null")
            append(",\"touchReady\":")
            append(touchInputReady())
            append('}')
            append(",\"projection\":")
            append(
                BrowserProjectionProfileApi.snapshotJsonString(
                    snapshot = projection.profile,
                    viewport = projection.viewport,
                ),
            )
            append(",\"microphone\":{\"captureRequested\":")
            append(microphoneSource?.isCaptureRequested() == true)
            append('}')
            append(",\"browserSession\":")
            append(browserSessionJson(device))
            append('}')
        }.toByteArray(StandardCharsets.UTF_8)
        writeResponse(output, 200, "application/json; charset=utf-8", body)
    }

    private fun serveNotices(output: OutputStream) {
        val body = runCatching { noticeFeed().toJsonString().toByteArray(StandardCharsets.UTF_8) }
            .getOrElse {
                BrowserNoticeFeed.UNAVAILABLE.toJsonString().toByteArray(StandardCharsets.UTF_8)
            }
        val boundedBody = body.takeIf { it.size <= BrowserNoticeFeed.MAX_RESPONSE_BYTES }
            ?: BrowserNoticeFeed.UNAVAILABLE.toJsonString().toByteArray(StandardCharsets.UTF_8)
        writeResponse(output, 200, "application/json; charset=utf-8", boundedBody)
    }

    private fun serveWebRtcCapabilities(
        output: OutputStream,
        includeCloudLanStun: Boolean,
    ) {
        val capabilities = runCatching { webRtcSignaling.capabilities() }.getOrElse {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"webrtc_capabilities_failed\"}".toByteArray(),
            )
            return
        }
        val iceServers = if (includeCloudLanStun) {
            (capabilities.iceServers + listOfNotNull(runCatching(cloudLanStunIceServer).getOrNull()))
                .distinct()
        } else {
            capabilities.iceServers
        }
        val projection = coherentProjectionSnapshot() ?: run {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"projection_reconfiguring\"}".toByteArray(),
            )
            return
        }
        val body = buildString {
            append("{\"available\":")
            append(capabilities.available)
            append(",\"codecs\":[")
            capabilities.codecs.forEachIndexed { index, codec ->
                if (index > 0) append(',')
                appendJsonString(codec.wireName)
            }
            append("],\"outputAudioDataChannelsV1\":[")
            capabilities.outputAudioDataChannelsV1.forEachIndexed { index, track ->
                if (index > 0) append(',')
                appendJsonString(track)
            }
            append("],\"controlDataChannelV1\":")
            append(capabilities.controlDataChannelV1)
            append(",\"iceServers\":[")
            iceServers.forEachIndexed { index, server ->
                if (index > 0) append(',')
                append("{\"urls\":[")
                server.urls.forEachIndexed { urlIndex, url ->
                    if (urlIndex > 0) append(',')
                    appendJsonString(url)
                }
                append(']')
                server.username?.let {
                    append(",\"username\":")
                    appendJsonString(it)
                }
                server.credential?.let {
                    append(",\"credential\":")
                    appendJsonString(it)
                }
                append('}')
            }
            append("],\"signalingMode\":\"non-trickle\",\"detail\":")
            appendJsonString(capabilities.detail)
            append(",\"source\":")
            val activeProfile = projection.profile.activeProfile
            val activeViewport = projection.viewport.activeLayout.encodedViewport
            append(
                BrowserProjectionProfileApi.profileJson(
                    profile = activeProfile,
                    viewport = activeViewport,
                    densityDpi = projection.viewport.activeLayout.densityDpi,
                ),
            )
            append('}')
        }.toByteArray(StandardCharsets.UTF_8)
        writeResponse(output, 200, "application/json; charset=utf-8", body)
    }

    private fun serveProjectionProfile(output: OutputStream) {
        val body = runCatching {
            val projection = checkNotNull(coherentProjectionSnapshot())
            BrowserProjectionProfileApi.snapshotJsonString(
                snapshot = projection.profile,
                viewport = projection.viewport,
            ).toByteArray(StandardCharsets.UTF_8)
        }.getOrElse {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"projection_profile_unavailable\"}".toByteArray(),
            )
            return
        }
        writeResponse(output, 200, "application/json; charset=utf-8", body)
    }

    private fun coherentProjectionSnapshot(): CoherentProjectionSnapshot? {
        repeat(PROJECTION_SNAPSHOT_ATTEMPTS) {
            val before = projectionProfiles.snapshot()
            val viewport = projectionViewport.snapshot()
            val after = projectionProfiles.snapshot()
            if (
                before == after &&
                after.activeProfile.supportsEncodedViewport(
                    viewport.activeLayout.encodedViewport,
                )
            ) {
                return CoherentProjectionSnapshot(after, viewport)
            }
        }
        return null
    }

    private fun serveProjectionViewport(output: OutputStream) {
        val body = runCatching {
            BrowserProjectionViewportApi.snapshotJsonString(projectionViewport.snapshot())
                .toByteArray(StandardCharsets.UTF_8)
        }.getOrElse {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"projection_viewport_unavailable\"}".toByteArray(),
            )
            return
        }
        writeResponse(output, 200, "application/json; charset=utf-8", body)
    }

    private fun serveProjectionViewportRequest(
        request: HttpRequest,
        output: OutputStream,
    ): Unit = synchronized(pairingCodeOperationLock) {
        val device = authenticatedDevice(request) ?: run {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        if (device.device.permission == BrowserDevicePermission.READ_ONLY) {
            writeResponse(
                output,
                403,
                JSON_CONTENT_TYPE,
                "{\"error\":\"read_only_session\"}".toByteArray(),
            )
            return
        }
        val preferredMain = browserTrustStore.preferredMainDeviceId()
            ?: webRtcSignaling.oldestInteractiveDeviceId()?.let { candidate ->
                when (val claim = browserTrustStore.setPreferredMainIfUnset(candidate)) {
                    is PairedBrowserMutationResult.Updated -> claim.preferredMainDeviceId
                    else -> browserTrustStore.preferredMainDeviceId()
                }
            }
        if (preferredMain == null || preferredMain != device.device.deviceId) {
            writeResponse(
                output,
                409,
                JSON_CONTENT_TYPE,
                "{\"error\":\"main_session_required\"}".toByteArray(),
            )
            return
        }
        val suppliedClientId = request.headers["x-viewport-client-id"]
        val viewportClientId = when {
            suppliedClientId == null -> {
                writeResponse(
                    output,
                    428,
                    "application/json; charset=utf-8",
                    "{\"error\":\"viewport_client_upgrade_required\"}".toByteArray(),
                )
                return
            }
            isValidViewportClientId(suppliedClientId) -> suppliedClientId
            else -> {
                writeResponse(
                    output,
                    422,
                    "application/json; charset=utf-8",
                    "{\"error\":\"invalid_viewport_client_id\"}".toByteArray(),
                )
                return
            }
        }
        val width = parseViewportDimension(request.query["width"])
        val height = parseViewportDimension(request.query["height"])
        val devicePixelRatio = when (val raw = request.query["devicePixelRatio"]) {
            null -> 1.0
            else -> parseViewportDevicePixelRatio(raw) ?: run {
                writeResponse(
                    output,
                    422,
                    "application/json; charset=utf-8",
                    "{\"error\":\"invalid_projection_viewport\"}".toByteArray(),
                )
                return
            }
        }
        val response = runCatching {
            BrowserProjectionViewportApi.response(
                projectionViewport.requestViewport(width, height, devicePixelRatio),
            )
        }.getOrElse {
            if (BuildConfig.DEBUG) {
                Log.w(
                    VIEWPORT_LOG_TAG,
                    "VIEWPORT_HTTP_FAILED width=$width height=$height dpr=$devicePixelRatio",
                )
            }
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"projection_viewport_request_failed\"}".toByteArray(),
            )
            return
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                VIEWPORT_LOG_TAG,
                "VIEWPORT_HTTP status=${response.status} width=$width height=$height " +
                    "dpr=$devicePixelRatio",
            )
        }
        writeResponse(
            output,
            response.status,
            "application/json; charset=utf-8",
            response.body,
        )
    }

    private fun refreshViewportController(request: HttpRequest) {
        // Main-device ownership is persistent in BrowserTrustStore. Status polling no longer
        // transfers a short-lived viewport lease between tabs or devices.
    }

    private fun serveWebRtcOpen(
        request: HttpRequest,
        directPeerIpv4: String?,
        output: OutputStream,
    ) {
        val contentType = request.headers["content-type"]
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (contentType != SDP_CONTENT_TYPE) {
            writeResponse(
                output,
                415,
                "application/json; charset=utf-8",
                "{\"error\":\"sdp_content_type_required\"}".toByteArray(),
            )
            return
        }

        val browserRequestedCodec = parseWebRtcCodec(request.query["codec"])
        val originalSdp = request.body.toString(StandardCharsets.UTF_8)
        if (browserRequestedCodec == null || !isValidWebRtcOfferSdp(originalSdp)) {
            writeResponse(
                output,
                422,
                "application/json; charset=utf-8",
                "{\"error\":\"invalid_webrtc_offer\"}".toByteArray(),
            )
            return
        }
        // A cloud-relayed request has no LAN socket peer address. Chrome still supplies a
        // private srflx candidate discovered through the phone's LAN STUN server, though. When
        // that one unambiguous address shares a UDP port with the mDNS host candidates, it is the
        // same browser endpoint and can safely replace the unresolvable mDNS tokens. Ambiguous,
        // public, loopback, or port-mismatched offers remain byte-for-byte unchanged.
        val resolvedDirectPeerIpv4 = directPeerIpv4
            ?: inferDirectPeerIpv4FromPrivateSrflx(originalSdp)
        val sdp = rewriteMdnsHostCandidateAddresses(originalSdp, resolvedDirectPeerIpv4)
        val phonePreferredCodec = runCatching(preferredWebRtcCodec)
            .getOrDefault(BrowserWebRtcCodec.AUTO)
        val browserCredential = request.headers["x-browser-credential"]
        if (browserCredential == null) {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        val authenticatedOpen = when (
            val admission = commitAuthenticatedBrowserOperation(
                lock = pairingCodeOperationLock,
                authenticate = { browserTrustStore.authenticate(browserCredential) },
                operation = { fresh ->
                    fresh to runCatching {
                        webRtcSignaling.openSession(
                            BrowserWebRtcOffer(
                                preferredCodec = phonePreferredCodec,
                                sdp = sdp,
                                ownerKey = fresh.ownerKey,
                                deviceId = fresh.device.deviceId,
                                accessMode = fresh.device.permission.toSessionAccessMode(),
                                colorSlot = fresh.device.colorSlot,
                                displayName = fresh.device.displayName,
                            ),
                        )
                    }
                },
            )
        ) {
            BrowserCredentialAdmissionResult.Unauthorized -> {
                writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
                return
            }
            is BrowserCredentialAdmissionResult.Authorized -> admission.value
        }
        val authenticated = authenticatedOpen.first
        val openAttempt = authenticatedOpen.second
        when (
            val result = openAttempt.getOrElse {
                writeResponse(
                    output,
                    503,
                    "application/json; charset=utf-8",
                    "{\"error\":\"webrtc_open_failed\"}".toByteArray(),
                )
                return
            }
        ) {
            is BrowserWebRtcOpenResult.Accepted -> {
                recordDeviceSeen(authenticated)
                writeResponse(
                    output,
                    201,
                    "application/json; charset=utf-8",
                    webRtcSessionJson(result.session),
                )
            }
            BrowserWebRtcOpenResult.Busy -> {
                writeResponse(
                    output,
                    429,
                    "application/json; charset=utf-8",
                    "{\"error\":\"webrtc_busy\"}".toByteArray(),
                    extraHeaders = mapOf("Retry-After" to "1"),
                )
            }
            is BrowserWebRtcOpenResult.Rejected -> {
                val body = buildString {
                    append("{\"error\":\"webrtc_rejected\",\"detail\":")
                    appendJsonString(result.detail)
                    append('}')
                }.toByteArray(StandardCharsets.UTF_8)
                writeResponse(output, 409, "application/json; charset=utf-8", body)
            }
        }
    }

    private fun serveWebRtcSession(request: HttpRequest, output: OutputStream) {
        val sessionId = webRtcSessionId(request.path)
        if (sessionId == null) {
            writeResponse(output, 404, "application/json", "{\"error\":\"not_found\"}".toByteArray())
            return
        }
        val authenticated = authenticatedDevice(request)
        if (authenticated == null) {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        val current = runCatching {
            webRtcSignaling.session(sessionId, authenticated.ownerKey)
        }.getOrElse {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"webrtc_session_failed\"}".toByteArray(),
            )
            return
        }
        if (current == null || current.sessionId != sessionId) {
            writeResponse(output, 404, "application/json", "{\"error\":\"not_found\"}".toByteArray())
            return
        }
        writeResponse(
            output,
            200,
            "application/json; charset=utf-8",
            webRtcSessionJson(current, authenticated.device.displayName),
        )
    }

    private fun serveWebRtcClose(request: HttpRequest, output: OutputStream) {
        val sessionId = webRtcSessionId(request.path)
        if (sessionId == null) {
            writeResponse(output, 404, "application/json", "{\"error\":\"not_found\"}".toByteArray())
            return
        }
        val authenticated = authenticatedDevice(request)
        if (authenticated == null) {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        val closed = runCatching {
            webRtcSignaling.closeSession(sessionId, authenticated.ownerKey)
        }.getOrDefault(false)
        if (closed) {
            writeResponse(output, 204, "application/json", ByteArray(0))
        } else {
            writeResponse(output, 404, "application/json", "{\"error\":\"not_found\"}".toByteArray())
        }
    }

    private fun webRtcSessionJson(
        session: BrowserWebRtcSessionSnapshot,
        displayNameOverride: String? = null,
    ): ByteArray = buildString {
        append("{\"sessionId\":")
        appendJsonString(session.sessionId)
        append(",\"state\":")
        appendJsonString(session.state.wireName)
        session.selectedCodec?.let {
            append(",\"selectedCodec\":")
            appendJsonString(it.wireName)
        }
        session.answerSdp?.let {
            append(",\"answerSdp\":")
            appendJsonString(it)
        }
        if (session.detail.isNotEmpty()) {
            append(",\"detail\":")
            appendJsonString(session.detail)
        }
        session.publicMetadata?.let { metadata ->
            append(",\"browserSession\":{")
            append("\"deviceId\":")
            appendJsonString(metadata.deviceId)
            append(",\"access\":")
            appendJsonString(metadata.accessMode.wireName)
            append(",\"role\":")
            appendJsonString(if (metadata.isMain) "main" else "viewer")
            append(",\"colorSlot\":").append(metadata.colorSlot)
            val displayName = displayNameOverride ?: metadata.displayName
            if (displayName.isNotEmpty()) {
                append(",\"displayName\":")
                appendJsonString(displayName)
            }
            append('}')
        }
        append('}')
    }.toByteArray(StandardCharsets.UTF_8)

    private fun serveFrame(request: HttpRequest, output: OutputStream) {
        val afterVersion = parseFrameVersion(request.query["after"])
        if (afterVersion == null) {
            writeResponse(
                output,
                422,
                "application/json; charset=utf-8",
                "{\"error\":\"invalid_frame_version\"}".toByteArray(),
            )
            return
        }
        if (!frameClientSlots.tryAcquire()) {
            writeResponse(
                output,
                429,
                "application/json; charset=utf-8",
                "{\"error\":\"frame_clients_busy\"}".toByteArray(),
                extraHeaders = mapOf("Retry-After" to "1"),
            )
            return
        }

        try {
            val subscription = runCatching { frameStore.subscribe() }.getOrNull()
            if (subscription == null) {
                writeResponse(output, 204, "image/jpeg", ByteArray(0))
                return
            }
            subscription.use {
                val current = frameStore.latest()
                val frame = when {
                    shouldServeFrameImmediately(current?.version, afterVersion) -> current
                    else -> runCatching {
                        frameStore.awaitNext(afterVersion, FRAME_WAIT_MILLIS)
                    }.getOrNull()
                }
                if (frame == null) {
                    writeResponse(output, 204, "image/jpeg", ByteArray(0))
                } else {
                    writeFrameResponse(output, frame)
                }
            }
        } finally {
            frameClientSlots.release()
        }
    }

    private fun serveAudio(request: HttpRequest, socket: Socket) {
        val output = socket.getOutputStream()
        val track = audioStreamTrack(request.path)
        if (track == null) {
            writeResponse(output, 404, "application/json", "{\"error\":\"not_found\"}".toByteArray())
            return
        }
        val hub = audioStreams
        if (hub == null) {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"audio_stream_unavailable\"}".toByteArray(),
            )
            return
        }
        if (androidAutoConnection().state != AndroidAutoConnectionState.CONNECTED) {
            writeResponse(
                output,
                409,
                "application/json; charset=utf-8",
                "{\"error\":\"android_auto_not_connected\"}".toByteArray(),
            )
            return
        }
        if (!audioClientSlots.tryAcquire()) {
            writeResponse(
                output,
                429,
                "application/json; charset=utf-8",
                "{\"error\":\"audio_clients_busy\"}".toByteArray(),
                extraHeaders = mapOf("Retry-After" to "1"),
            )
            return
        }

        val subscription = hub.subscribe(track)
        if (subscription == null) {
            audioClientSlots.release()
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"error\":\"audio_stream_closed\"}".toByteArray(),
            )
            return
        }
        val lastWriteProgressMillis = AtomicLong(SystemClock.elapsedRealtime())
        val writeWatchdog = scope.launch {
            while (isActive && !socket.isClosed) {
                delay(AUDIO_WRITE_WATCHDOG_CHECK_MILLIS)
                if (
                    isAudioWriteStalled(
                        nowMillis = SystemClock.elapsedRealtime(),
                        lastProgressMillis = lastWriteProgressMillis.get(),
                    )
                ) {
                    runCatching(socket::close)
                    break
                }
            }
        }
        try {
            writeAudioStreamHeaders(output, subscription)
            lastWriteProgressMillis.set(SystemClock.elapsedRealtime())
            while (scope.isActive && !subscription.isClosed &&
                androidAutoConnection().state == AndroidAutoConnectionState.CONNECTED
            ) {
                val chunk = subscription.poll(AUDIO_POLL_MILLIS)
                    ?: audioIdleHeartbeat(subscription.format.channelCount)
                writeChunk(output, chunk)
                lastWriteProgressMillis.set(SystemClock.elapsedRealtime())
            }
            writeChunkedEnd(output)
        } finally {
            writeWatchdog.cancel()
            subscription.close()
            audioClientSlots.release()
        }
    }

    private fun serveMicrophone(
        request: HttpRequest,
        remoteAddress: InetAddress,
        output: OutputStream,
    ): Unit = synchronized(pairingCodeOperationLock) {
        val device = authenticatedDevice(request) ?: run {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        if (device.device.permission == BrowserDevicePermission.READ_ONLY) {
            writeResponse(
                output,
                403,
                JSON_CONTENT_TYPE,
                "{\"accepted\":false,\"error\":\"read_only_session\"}".toByteArray(),
            )
            return
        }
        // This server is currently plain HTTP. Powerful browser media APIs allow
        // loopback as a trustworthy development origin, but LAN microphone PCM
        // must wait for the separately provisioned, browser-trusted HTTPS server.
        if (!isPlainHttpMicrophonePeerAllowed(remoteAddress)) {
            writeResponse(
                output,
                403,
                "application/json; charset=utf-8",
                "{\"accepted\":false,\"error\":\"microphone_requires_https\"}".toByteArray(),
            )
            return
        }
        val source = microphoneSource
        if (source == null) {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"accepted\":false,\"error\":\"microphone_stream_unavailable\"}".toByteArray(),
            )
            return
        }
        val upload = decodeMicrophoneUpload(request.headers, request.body)
        if (upload == null) {
            writeResponse(
                output,
                422,
                "application/json; charset=utf-8",
                "{\"accepted\":false,\"error\":\"invalid_microphone_chunk\"}".toByteArray(),
            )
            return
        }
        if (!source.acceptPcm16LittleEndian(upload.format, upload.pcm16LittleEndian)) {
            writeResponse(
                output,
                503,
                "application/json; charset=utf-8",
                "{\"accepted\":false,\"error\":\"microphone_stream_closed\"}".toByteArray(),
            )
            return
        }
        writeResponse(
            output,
            202,
            "application/json; charset=utf-8",
            "{\"accepted\":true}".toByteArray(),
        )
    }

    private fun servePair(request: HttpRequest, output: OutputStream) {
        synchronized(pairingCodeOperationLock) {
            if (pairingCodePublication.currentCode() == null) {
                writeResponse(
                    output,
                    409,
                    "application/json; charset=utf-8",
                    "{\"error\":\"pairing_not_requested\"}".toByteArray(),
                )
                return@synchronized
            }
            val verification = pairingCodeGate.verifyAndRotate(request.headers["x-pairing-code"])
            when (verification.decision) {
                PairingCodeDecision.INVALID -> {
                    writeResponse(output, 401, "application/json", "{\"error\":\"invalid_pairing_code\"}".toByteArray())
                    return@synchronized
                }
                PairingCodeDecision.EXPIRED -> {
                    verification.replacementCode?.let { replacementCode ->
                        publishPairingCode(
                            pairingCodePublication.afterRotation(
                                code = replacementCode,
                                keepOpen = false,
                            ),
                        )
                    }
                    writeResponse(output, 410, "application/json", "{\"error\":\"pairing_code_expired\"}".toByteArray())
                    return@synchronized
                }
                PairingCodeDecision.LOCKED -> {
                    writeResponse(output, 429, "application/json", "{\"error\":\"pairing_locked\"}".toByteArray())
                    return@synchronized
                }
                PairingCodeDecision.ACCEPTED -> Unit
            }

            when (
                val issue = runCatching {
                    issuePairedBrowserDeviceFromPairingHeaders(
                        browserTrustStore = browserTrustStore,
                        headers = request.headers,
                    )
                }.getOrDefault(BrowserCredentialIssueResult.PersistenceFailed)
            ) {
                is BrowserCredentialIssueResult.Issued -> {
                    if (pairingCodePublication.close()) publishPairingCode(null)
                    val grant = issue.grant
                    val body = buildString {
                        append("{\"browserCredential\":")
                        appendJsonString(grant.credential)
                        append(",\"browserSession\":")
                        append(browserSessionJson(grant.authenticatedDevice))
                        append('}')
                    }
                        .toByteArray(StandardCharsets.UTF_8)
                    writeResponse(output, 200, "application/json; charset=utf-8", body)
                }
                BrowserCredentialIssueResult.CapacityReached -> {
                    verification.replacementCode?.let { replacementCode ->
                        publishPairingCode(
                            pairingCodePublication.afterRotation(
                                code = replacementCode,
                                keepOpen = true,
                            ),
                        )
                    }
                    writeResponse(
                        output,
                        409,
                        JSON_CONTENT_TYPE,
                        "{\"error\":\"paired_device_capacity_reached\"}".toByteArray(),
                    )
                }
                BrowserCredentialIssueResult.PersistenceFailed -> {
                    verification.replacementCode?.let { replacementCode ->
                        publishPairingCode(
                            pairingCodePublication.afterRotation(
                                code = replacementCode,
                                keepOpen = true,
                            ),
                        )
                    }
                    writeResponse(output, 500, "application/json", "{\"error\":\"pairing_store_failed\"}".toByteArray())
                }
            }
        }
    }

    private fun publishPairingCode(code: String?) {
        val lease = if (code == null) {
            null
        } else {
            pairingCodeGate.currentLease().takeIf { it.code == code } ?: return
        }
        runCatching { onPairingCodePublicationChanged(lease) }
    }

    private fun publishedPairingCodeLeaseLocked(): PairingCodeLease? {
        val publishedCode = pairingCodePublication.currentCode() ?: return null
        return pairingCodeGate.currentLease().takeIf { it.code == publishedCode }
    }

    private fun serveTouch(
        request: HttpRequest,
        output: OutputStream,
    ): Unit = synchronized(pairingCodeOperationLock) {
        val device = authenticatedDevice(request) ?: run {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        if (device.device.permission == BrowserDevicePermission.READ_ONLY) {
            writeResponse(
                output,
                403,
                JSON_CONTENT_TYPE,
                "{\"accepted\":false,\"reason\":\"read_only_session\"}".toByteArray(),
            )
            return
        }
        val connection = androidAutoConnection()
        val inputReady = touchInputReady()
        if (!isTouchAllowed(connection, inputReady)) {
            val reason = if (connection.state == AndroidAutoConnectionState.CONNECTED) {
                "input_not_ready"
            } else {
                "android_auto_not_connected"
            }
            writeResponse(
                output,
                409,
                "application/json; charset=utf-8",
                "{\"accepted\":false,\"reason\":\"$reason\"}".toByteArray(),
            )
            return
        }
        val phase = when (request.query["phase"]?.lowercase()) {
            "down" -> TouchPhase.DOWN
            "move" -> TouchPhase.MOVE
            "up" -> TouchPhase.UP
            "cancel" -> TouchPhase.CANCEL
            else -> null
        }
        val x = request.query["x"]?.toDoubleOrNull()
        val y = request.query["y"]?.toDoubleOrNull()
        // Browser coordinates are normalized against #projection-content. OpenAutoCoordinator is
        // the single owner of mapping that content space through the negotiated AA margins.
        val contentCoordinateSpace = isContentTouchCoordinateSpace(
            request.query["coordinateSpace"],
        )
        val pointerId = request.query["pointerId"]?.toIntOrNull()
            ?: if (request.query.containsKey("pointerId")) null else BROWSER_POINTER_ID

        if (!contentCoordinateSpace || !isValidBrowserTouch(phase, x, y, pointerId)) {
            writeResponse(output, 422, "application/json", "{\"error\":\"invalid_touch\"}".toByteArray())
            return
        }

        val touchPhase = checkNotNull(phase)
        val normalizedTouch = NormalizedTouch(
            phase = touchPhase,
            x = checkNotNull(x),
            y = checkNotNull(y),
            pointerId = checkNotNull(pointerId),
        )
        val liveMetadata = webRtcSignaling.activeSessionMetadata(device.ownerKey)
        val gestureSource = BrowserTouchGestureSource(
            ownerKey = device.ownerKey,
            deviceId = device.device.deviceId,
            colorSlot = liveMetadata?.colorSlot ?: device.device.colorSlot,
        )
        when (
            touchGestureGate.tryAdmit(
                source = gestureSource,
                touch = normalizedTouch,
                cancelExpiredGesture = { expiredSource, expiredTouch ->
                    val cancelTouch = expiredTouch.copy(
                        phase = TouchPhase.CANCEL,
                        timestampNanos = System.nanoTime(),
                    )
                    val cancelled = runCatching { onTouch(cancelTouch) }.getOrDefault(false)
                    if (cancelled) {
                        runCatching {
                            webRtcSignaling.publishTouchPresence(
                                sourceOwnerKey = expiredSource.ownerKey,
                                event = BrowserTouchPresenceEvent(
                                    sourceDeviceId = expiredSource.deviceId,
                                    colorSlot = expiredSource.colorSlot,
                                    phase = "cancel",
                                    x = cancelTouch.x,
                                    y = cancelTouch.y,
                                    sequence = nextTouchPresenceSequence(),
                                ),
                            )
                        }
                    }
                    cancelled
                },
            )
        ) {
            BrowserTouchGestureAdmission.ACCEPTED -> Unit
            BrowserTouchGestureAdmission.BUSY -> {
                writeResponse(
                    output,
                    409,
                    JSON_CONTENT_TYPE,
                    "{\"accepted\":false,\"reason\":\"touch_gesture_busy\"}".toByteArray(),
                )
                return
            }
            BrowserTouchGestureAdmission.OUT_OF_SEQUENCE -> {
                writeResponse(
                    output,
                    409,
                    JSON_CONTENT_TYPE,
                    "{\"accepted\":false,\"reason\":\"touch_gesture_out_of_sequence\"}"
                        .toByteArray(),
                )
                return
            }
            BrowserTouchGestureAdmission.RECOVERY_REJECTED -> {
                writeResponse(
                    output,
                    409,
                    JSON_CONTENT_TYPE,
                    "{\"accepted\":false,\"reason\":\"touch_gesture_recovery_rejected\"}"
                        .toByteArray(),
                )
                return
            }
        }
        val accepted = runCatching {
            onTouch(normalizedTouch)
        }.getOrDefault(false)
        val status = if (accepted) 202 else 409
        val body = if (accepted) {
            runCatching {
                webRtcSignaling.publishTouchPresence(
                    sourceOwnerKey = device.ownerKey,
                    event = BrowserTouchPresenceEvent(
                        sourceDeviceId = gestureSource.deviceId,
                        colorSlot = gestureSource.colorSlot,
                        phase = touchPhase.name.lowercase(),
                        x = checkNotNull(x),
                        y = checkNotNull(y),
                        sequence = nextTouchPresenceSequence(),
                    ),
                )
            }
            "{\"accepted\":true}"
        } else {
            "{\"accepted\":false,\"reason\":\"queue_rejected\"}"
        }
        // Keep the pointer lease until the corresponding presence event has been sequenced and
        // queued. Together with pairingCodeOperationLock this preserves native/presence ordering
        // when ownership changes between browser sessions.
        touchGestureGate.complete(device.ownerKey, touchPhase, accepted)
        writeResponse(output, status, "application/json", body.toByteArray())
    }

    /** WebRTC-only Android Auto key input. The local HTTP router intentionally has no route. */
    private fun serveKey(
        request: HttpRequest,
        output: OutputStream,
    ): Unit = synchronized(pairingCodeOperationLock) {
        val device = authenticatedDevice(request) ?: run {
            writeResponse(output, 401, JSON_CONTENT_TYPE, ERROR_INVALID_BROWSER_CREDENTIAL)
            return
        }
        if (device.device.permission == BrowserDevicePermission.READ_ONLY) {
            writeResponse(
                output,
                403,
                JSON_CONTENT_TYPE,
                "{\"accepted\":false,\"reason\":\"read_only_session\"}".toByteArray(),
            )
            return
        }
        val connection = androidAutoConnection()
        val inputReady = keyInputReady()
        if (!isKeyInputAllowed(connection, inputReady)) {
            val reason = if (connection.state == AndroidAutoConnectionState.CONNECTED) {
                "input_not_ready"
            } else {
                "android_auto_not_connected"
            }
            writeResponse(
                output,
                409,
                JSON_CONTENT_TYPE,
                "{\"accepted\":false,\"reason\":\"$reason\"}".toByteArray(),
            )
            return
        }

        val event = when (
            val decoded = decodeBrowserKeyInput(
                contentType = request.headers["content-type"],
                hasQueryParameters = request.hasQuery,
                body = request.body,
            )
        ) {
            is BrowserKeyInputDecodeResult.Accepted -> decoded.event
            BrowserKeyInputDecodeResult.PayloadTooLarge -> {
                writeResponse(
                    output,
                    413,
                    JSON_CONTENT_TYPE,
                    "{\"error\":\"key_payload_too_large\"}".toByteArray(),
                )
                return
            }
            BrowserKeyInputDecodeResult.UnsupportedMediaType -> {
                writeResponse(
                    output,
                    415,
                    JSON_CONTENT_TYPE,
                    "{\"error\":\"unsupported_key_content_type\"}".toByteArray(),
                )
                return
            }
            BrowserKeyInputDecodeResult.Invalid -> {
                writeResponse(
                    output,
                    422,
                    JSON_CONTENT_TYPE,
                    "{\"error\":\"invalid_key_input\"}".toByteArray(),
                )
                return
            }
        }

        val accepted = runCatching { onKey(event) }.getOrDefault(false)
        val status = if (accepted) 202 else 409
        val body = if (accepted) {
            "{\"accepted\":true}"
        } else {
            "{\"accepted\":false,\"reason\":\"queue_rejected\"}"
        }
        writeResponse(output, status, JSON_CONTENT_TYPE, body.toByteArray())
    }

    private fun authenticatedDevice(request: HttpRequest): AuthenticatedBrowserDevice? =
        browserTrustStore.authenticate(request.headers["x-browser-credential"])

    private fun isAuthorized(request: HttpRequest): Boolean = authenticatedDevice(request) != null

    private fun BrowserDevicePermission.toSessionAccessMode(): BrowserSessionAccessMode = when (this) {
        BrowserDevicePermission.CONTROL -> BrowserSessionAccessMode.CONTROL
        BrowserDevicePermission.READ_ONLY -> BrowserSessionAccessMode.READ_ONLY
    }

    private fun browserSessionJson(authenticated: AuthenticatedBrowserDevice): String {
        val liveMetadata = webRtcSignaling.activeSessionMetadata(authenticated.ownerKey)
        val device = authenticated.device
        return buildString(144) {
            append("{\"deviceId\":")
            appendJsonString(device.deviceId)
            append(",\"access\":")
            appendJsonString(device.permission.toSessionAccessMode().wireName)
            append(",\"role\":")
            appendJsonString(if (device.preferredMain) "main" else "viewer")
            append(",\"colorSlot\":")
            append(liveMetadata?.colorSlot ?: device.colorSlot)
            append(",\"displayName\":")
            appendJsonString(device.displayName)
            append('}')
        }
    }

    private fun recordDeviceSeen(authenticated: AuthenticatedBrowserDevice) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val shouldPersist = synchronized(deviceSeenLock) {
            val previous = lastDeviceSeenRecordMillis[authenticated.device.deviceId]
            if (previous != null &&
                (nowElapsed - previous).coerceAtLeast(0L) < DEVICE_SEEN_WRITE_INTERVAL_MILLIS
            ) {
                false
            } else {
                if (lastDeviceSeenRecordMillis.size >= MAX_TRACKED_SEEN_DEVICES) {
                    val oldest = lastDeviceSeenRecordMillis.minByOrNull { it.value }?.key
                    if (oldest != null) lastDeviceSeenRecordMillis.remove(oldest)
                }
                lastDeviceSeenRecordMillis[authenticated.device.deviceId] = nowElapsed
                true
            }
        }
        if (shouldPersist) {
            runCatching { browserTrustStore.recordSeen(authenticated.device.deviceId) }
        }
    }

    private fun nextTouchPresenceSequence(): Long = touchPresenceSequence.updateAndGet { current ->
        if (current >= MAX_JAVASCRIPT_SAFE_SEQUENCE) 1L else current + 1L
    }

    override fun close() {
        acceptJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        activeClientSockets.close()
        scope.cancel()
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val reason = when (status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            410 -> "Gone"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Content"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            appendSecurityHeaders()
            append(connectionHeaderFor(output))
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        output.write(body)
        output.flush()
    }

    /** Only the socket path opts into persistence; every other sink keeps Connection: close. */
    private fun connectionHeaderFor(output: OutputStream): String =
        if ((output as? HttpConnectionOutput)?.keepAlive == true) {
            "Connection: keep-alive\r\n\r\n"
        } else {
            "Connection: close\r\n\r\n"
        }

    private fun writeFrameResponse(output: OutputStream, frame: BrowserFrameSnapshot) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${frame.byteCount}\r\n")
            append("X-Frame-Version: ${frame.version}\r\n")
            appendSecurityHeaders()
            append(connectionHeaderFor(output))
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        frame.writeJpegTo(output)
        output.flush()
    }

    private fun writeAudioStreamHeaders(
        output: OutputStream,
        subscription: BrowserAudioSubscription,
    ) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("X-Audio-Codec: pcm-s16le\r\n")
            append("X-Audio-Sample-Rate: ${subscription.format.sampleRateHz}\r\n")
            append("X-Audio-Channels: ${subscription.format.channelCount}\r\n")
            append("X-Audio-Track: ${subscription.track.wireName}\r\n")
            appendSecurityHeaders()
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        output.flush()
    }

    private fun writeChunk(output: OutputStream, body: ByteArray) {
        if (body.isEmpty()) return
        output.write(body.size.toString(16).toByteArray(StandardCharsets.US_ASCII))
        output.write(CRLF)
        output.write(body)
        output.write(CRLF)
        output.flush()
    }

    private fun writeChunkedEnd(output: OutputStream) {
        output.write(CHUNKED_END)
        output.flush()
    }

    private fun StringBuilder.appendSecurityHeaders() {
        append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0, private\r\n")
        append("Pragma: no-cache\r\n")
        append("Expires: 0\r\n")
        append("Cross-Origin-Resource-Policy: same-origin\r\n")
        append("X-Content-Type-Options: nosniff\r\n")
        append("Content-Security-Policy: $CONTENT_SECURITY_POLICY\r\n")
        append("X-Frame-Options: DENY\r\n")
        append("Referrer-Policy: no-referrer\r\n")
        append("Permissions-Policy: microphone=(self), camera=(), geolocation=()\r\n")
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001f' -> {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                }
                else -> append(character)
            }
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        append(jsonEscape(value))
        append('"')
    }

    internal data class HttpRequest(
        val method: String,
        val path: String,
        val hasQuery: Boolean,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        /** Socket path only: HTTP/1.1 without an explicit Connection: close token. */
        val keepAliveCapable: Boolean = false,
    ) {
        companion object {
            fun fromRelay(request: BrowserRelayRequest): HttpRequest? {
                val uri = runCatching { URI(request.target) }.getOrNull() ?: return null
                if (
                    uri.isAbsolute ||
                    uri.rawAuthority != null ||
                    uri.rawFragment != null ||
                    uri.rawUserInfo != null
                ) return null
                return HttpRequest(
                    method = request.method,
                    path = uri.path ?: "/",
                    hasQuery = uri.rawQuery != null,
                    query = parseQuery(uri.rawQuery),
                    headers = request.headers,
                    body = request.body,
                )
            }

            /**
             * Parses one request directly from the socket byte stream. Header lines are ASCII;
             * the body is read as exactly Content-Length raw bytes, which both preserves binary
             * payloads and leaves the stream positioned at the next request when the connection
             * is kept alive.
             */
            fun read(input: InputStream): HttpRequest? {
                val requestLine = readBoundedLine(input, MAX_REQUEST_LINE) ?: return null
                val parts = requestLine.split(' ')
                if (parts.size != 3) return null

                val headers = linkedMapOf<String, String>()
                var headerCount = 0
                while (true) {
                    val line = readBoundedLine(input, MAX_HEADER_LINE) ?: return null
                    if (line.isEmpty()) break
                    if (headerCount >= MAX_HEADERS) return null
                    val delimiter = line.indexOf(':')
                    if (delimiter <= 0) return null
                    val name = line.substring(0, delimiter).trim().lowercase()
                    // Duplicate framing headers are a smuggling vector; reject them outright
                    // instead of letting a later value silently win.
                    if (name == "content-length" && headers.containsKey(name)) return null
                    headers[name] = line.substring(delimiter + 1).trim()
                    headerCount += 1
                }

                if (headers.containsKey("transfer-encoding")) return null
                val contentLength = headers["content-length"]?.let { rawLength ->
                    if (rawLength.isEmpty() || !rawLength.all(Char::isDigit)) return null
                    rawLength.toIntOrNull()?.takeIf { it in 0..MAX_BODY_BYTES } ?: return null
                } ?: 0
                val body = ByteArray(contentLength)
                var bodyOffset = 0
                while (bodyOffset < body.size) {
                    val count = input.read(body, bodyOffset, body.size - bodyOffset)
                    if (count < 0) return null
                    bodyOffset += count
                }

                val uri = runCatching { URI(parts[1]) }.getOrNull() ?: return null
                return HttpRequest(
                    method = parts[0].uppercase(),
                    path = uri.path ?: "/",
                    hasQuery = uri.rawQuery != null,
                    query = parseQuery(uri.rawQuery),
                    headers = headers,
                    body = body,
                    keepAliveCapable = parts[2].equals("HTTP/1.1", ignoreCase = true) &&
                        headers["connection"]
                            ?.split(',')
                            ?.none { token -> token.trim().equals("close", ignoreCase = true) } != false,
                )
            }

            private fun parseQuery(rawQuery: String?): Map<String, String> {
                if (rawQuery.isNullOrBlank()) return emptyMap()
                return rawQuery.split('&').mapNotNull { field ->
                    val parts = field.split('=', limit = 2)
                    val key = decode(parts[0])
                    if (key.isBlank()) return@mapNotNull null
                    key to decode(parts.getOrElse(1) { "" })
                }.toMap()
            }

            private fun decode(value: String): String =
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())

            private fun readBoundedLine(input: InputStream, maxLength: Int): String? {
                val line = StringBuilder(minOf(maxLength, 128))
                while (true) {
                    when (val value = input.read()) {
                        -1 -> return null
                        '\n'.code -> return line.toString()
                        '\r'.code -> Unit
                        else -> {
                            if (line.length >= maxLength) return null
                            line.append(value.toChar())
                        }
                    }
                }
            }

            private const val MAX_REQUEST_LINE = 4_096
            private const val MAX_HEADER_LINE = 4_096
            private const val MAX_HEADERS = 40
            private const val MAX_BODY_BYTES = 128 * 1024
        }
    }

    companion object {
        const val DEFAULT_PORT = 8787
        private const val DEVELOPMENT_VIEWPORT_FLAG_DISABLED =
            "const NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED = false;"
        private const val DEVELOPMENT_VIEWPORT_FLAG_ENABLED =
            "const NAVONWEB_DEVELOPMENT_VIEWPORT_ENABLED = true;"
        private const val PAIRING_CODE_REFRESH_INTERVAL_MILLIS = 1_000L
        internal const val MAX_FRAME_CLIENTS = 3
        internal const val MAX_AUDIO_CLIENTS = 9
        // Keep-alive lets each paired browser hold several idle sockets (Chrome opens up to six
        // per origin) on top of its long-lived audio streams, so the general pool is sized for
        // three interactive devices instead of assuming per-request connection churn.
        internal const val MAX_CLIENTS = 48
        internal const val MAX_ACCEPT_BACKLOG = 48
        private const val MAX_REQUESTS_PER_CONNECTION = 1_000
        private const val REQUEST_SOCKET_TIMEOUT_MILLIS = 3_000
        private const val PCM16_BYTES_PER_SAMPLE = 2
        private const val FRAME_WAIT_MILLIS = 850L
        private const val AUDIO_POLL_MILLIS = 1_000L
        private const val AUDIO_WRITE_WATCHDOG_CHECK_MILLIS = 1_000L
        private const val AUDIO_WRITE_STALL_TIMEOUT_MILLIS = 10_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val BROWSER_POINTER_ID = 0
        private const val MAX_VIEWPORT_DIMENSION = 16_384
        private const val MAX_VIEWPORT_DIMENSION_DIGITS = 5
        private const val MAX_VIEWPORT_DEVICE_PIXEL_RATIO_LENGTH = 16
        private const val MIN_VIEWPORT_CLIENT_ID_LENGTH = 16
        private const val MAX_VIEWPORT_CLIENT_ID_LENGTH = 64
        private const val VIEWPORT_LOG_TAG = "NavOnWebViewport"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private val ERROR_INVALID_RELAY_REQUEST =
            "{\"error\":\"invalid_relay_request\"}".toByteArray(StandardCharsets.UTF_8)
        private val ERROR_INVALID_RELAY_RESPONSE =
            "{\"error\":\"invalid_relay_response\"}".toByteArray(StandardCharsets.UTF_8)
        private val ERROR_INVALID_BROWSER_CREDENTIAL =
            "{\"error\":\"invalid_browser_credential\"}".toByteArray(StandardCharsets.UTF_8)
        private val ERROR_CLOUD_SIGNALING_ONLY =
            "{\"error\":\"cloud_relay_signaling_only\"}".toByteArray(StandardCharsets.UTF_8)

        /**
         * Keeps the checked-in/release asset fail-closed. A debug server enables the development
         * viewport API only when the exact, unique disabled declaration is still present.
         */
        internal fun renderAppScript(template: String, isDebugBuild: Boolean): String {
            if (!isDebugBuild) return template
            val markerOffset = template.indexOf(DEVELOPMENT_VIEWPORT_FLAG_DISABLED)
            if (markerOffset < 0 || template.indexOf(
                    DEVELOPMENT_VIEWPORT_FLAG_DISABLED,
                    markerOffset + DEVELOPMENT_VIEWPORT_FLAG_DISABLED.length,
                ) >= 0
            ) {
                return template
            }
            return template.replaceRange(
                markerOffset,
                markerOffset + DEVELOPMENT_VIEWPORT_FLAG_DISABLED.length,
                DEVELOPMENT_VIEWPORT_FLAG_ENABLED,
            )
        }

        internal fun parseFrameVersion(value: String?): Long? = when {
            value == null -> 0L
            value.isEmpty() -> null
            value.length > MAX_FRAME_VERSION_DIGITS -> null
            !value.all(Char::isDigit) -> null
            else -> value.toLongOrNull()
        }

        internal fun parseViewportDimension(value: String?): Int? = when {
            value.isNullOrEmpty() || value.length > MAX_VIEWPORT_DIMENSION_DIGITS -> null
            !value.all(Char::isDigit) -> null
            else -> value.toIntOrNull()?.takeIf { it in 1..MAX_VIEWPORT_DIMENSION }
        }

        internal fun parseViewportDevicePixelRatio(value: String?): Double? = when {
            value.isNullOrEmpty() || value.length > MAX_VIEWPORT_DEVICE_PIXEL_RATIO_LENGTH -> null
            else -> value.toDoubleOrNull()?.takeIf { ratio ->
                ratio.isFinite() &&
                    ratio in ProjectionViewportResolver.MIN_DEVICE_PIXEL_RATIO..
                    ProjectionViewportResolver.MAX_DEVICE_PIXEL_RATIO
            }
        }

        internal fun isValidViewportClientId(value: String): Boolean =
            value.length in MIN_VIEWPORT_CLIENT_ID_LENGTH..MAX_VIEWPORT_CLIENT_ID_LENGTH &&
                value.all { character ->
                    character in 'a'..'z' || character in 'A'..'Z' ||
                        character in '0'..'9' || character == '-' || character == '_'
                }

        internal fun isValidBrowserTouch(
            phase: TouchPhase?,
            x: Double?,
            y: Double?,
            pointerId: Int?,
        ): Boolean =
            phase != null &&
                x != null &&
                y != null &&
                x.isFinite() &&
                y.isFinite() &&
                x in 0.0..1.0 &&
                y in 0.0..1.0 &&
            pointerId == BROWSER_POINTER_ID

        internal fun isContentTouchCoordinateSpace(value: String?): Boolean =
            value == null || value.equals("content", ignoreCase = true)

        internal fun isTouchAllowed(
            connection: AndroidAutoConnectionStatus,
            touchInputReady: Boolean = false,
        ): Boolean =
            connection.state == AndroidAutoConnectionState.CONNECTED && touchInputReady

        internal fun isKeyInputAllowed(
            connection: AndroidAutoConnectionStatus,
            keyInputReady: Boolean = false,
        ): Boolean =
            connection.state == AndroidAutoConnectionState.CONNECTED && keyInputReady

        internal fun shouldServeFrameImmediately(
            currentVersion: Long?,
            afterVersion: Long,
        ): Boolean = currentVersion != null && currentVersion > afterVersion

        internal fun parseWebRtcCodec(value: String?): BrowserWebRtcCodec? =
            if (value == null) BrowserWebRtcCodec.AUTO else BrowserWebRtcCodec.fromWireName(value)

        internal fun audioStreamTrack(path: String): BrowserAudioTrack? = when (path) {
            "/api/audio/media" -> BrowserAudioTrack.MEDIA
            "/api/audio/speech" -> BrowserAudioTrack.SPEECH
            "/api/audio/system" -> BrowserAudioTrack.SYSTEM
            else -> null
        }

        /** RFC 9110 token match: gzip is acceptable unless it carries an explicit q=0. */
        internal fun acceptEncodingAllowsGzip(header: String?): Boolean =
            header?.split(',')?.any { token ->
                val parameters = token.split(';')
                if (!parameters[0].trim().equals("gzip", ignoreCase = true)) return@any false
                val quality = parameters.asSequence()
                    .drop(1)
                    .map { parameter -> parameter.trim() }
                    .firstOrNull { parameter -> parameter.startsWith("q=", ignoreCase = true) }
                    ?.substring(2)
                    ?.trim()
                quality == null || (quality.toDoubleOrNull() ?: 0.0) > 0.0
            } == true

        internal fun decodeMicrophoneUpload(
            headers: Map<String, String>,
            body: ByteArray,
        ): BrowserMicrophoneUpload? {
            val contentType = headers["content-type"]
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (contentType != MICROPHONE_CONTENT_TYPE ||
                headers["x-audio-codec"]?.trim()?.lowercase() != MICROPHONE_CODEC ||
                body.isEmpty() || body.size > MAX_MICROPHONE_BASE64_BYTES
            ) {
                return null
            }
            val sampleRate = headers["x-audio-sample-rate"]
                ?.takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }
                ?.toIntOrNull()
                ?.takeIf { value -> value in Pcm16Format.MIN_SAMPLE_RATE_HZ..Pcm16Format.MAX_SAMPLE_RATE_HZ }
                ?: return null
            val channels = headers["x-audio-channels"]
                ?.takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }
                ?.toIntOrNull()
                ?.takeIf { value -> value in 1..Pcm16Format.MAX_CHANNEL_COUNT }
                ?: return null
            val pcm = runCatching {
                Base64.getDecoder().decode(String(body, StandardCharsets.US_ASCII))
            }.getOrNull() ?: return null
            val bytesPerFrame = channels * PCM16_BYTES_PER_SAMPLE
            if (pcm.isEmpty() || pcm.size > MAX_MICROPHONE_PCM_BYTES || pcm.size % bytesPerFrame != 0) {
                pcm.fill(0)
                return null
            }
            return BrowserMicrophoneUpload(
                format = Pcm16Format(sampleRateHz = sampleRate, channelCount = channels),
                pcm16LittleEndian = pcm,
            )
        }

        internal fun isPlainHttpMicrophonePeerAllowed(address: InetAddress): Boolean =
            address.isLoopbackAddress

        /** One silent PCM frame makes a disconnected idle browser observable on the next write. */
        internal fun audioIdleHeartbeat(channelCount: Int): ByteArray {
            require(channelCount in 1..2) { "unsupported audio heartbeat channel count" }
            return ByteArray(channelCount * PCM16_BYTES_PER_SAMPLE)
        }

        internal fun isAudioWriteStalled(nowMillis: Long, lastProgressMillis: Long): Boolean =
            (nowMillis - lastProgressMillis).coerceAtLeast(0L) >=
                AUDIO_WRITE_STALL_TIMEOUT_MILLIS

        internal fun isValidWebRtcSessionId(value: String): Boolean =
            value.length in MIN_WEBRTC_SESSION_ID_LENGTH..MAX_WEBRTC_SESSION_ID_LENGTH &&
                value.all {
                    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                }

        internal fun webRtcSessionId(path: String): String? {
            if (!path.startsWith(WEBRTC_SESSION_PATH_PREFIX)) return null
            val sessionId = path.removePrefix(WEBRTC_SESSION_PATH_PREFIX)
            return sessionId.takeIf(::isValidWebRtcSessionId)
        }

        internal fun isWebRtcSessionPath(path: String): Boolean = webRtcSessionId(path) != null

        internal fun isCloudSignalingRelayRequest(method: String, path: String): Boolean = when {
            method == "GET" && path in setOf(
                "/health",
                "/api/status",
                "/api/projection/profile",
                "/api/projection/viewport",
                "/api/webrtc/capabilities",
            ) -> true
            method == "POST" && path in setOf(
                "/api/pair",
                "/api/projection/viewport",
                "/api/webrtc/session",
            ) -> true
            method in setOf("GET", "DELETE") && isWebRtcSessionPath(path) -> true
            else -> false
        }

        internal fun isValidWebRtcOfferSdp(sdp: String): Boolean {
            if (sdp.isBlank() || sdp.length > MAX_WEBRTC_SDP_CHARS || '\u0000' in sdp) return false
            val lines = sdp.lineSequence().map(String::trim).toList()
            return lines.any { it == "v=0" } &&
                lines.any { it.startsWith("m=video ") } &&
                lines.any { it == "a=recvonly" } &&
                lines.any { it.startsWith("a=ice-ufrag:") } &&
                lines.any { it.startsWith("a=fingerprint:") }
        }

        /**
         * Replaces only the address token of browser mDNS UDP host candidates.
         * Invalid/loopback peer addresses and malformed candidate lines are
         * returned byte-for-byte unchanged.
         */
        internal fun rewriteMdnsHostCandidateAddresses(
            sdp: String,
            directPeerIpv4: String?,
        ): String {
            if (!isEligibleDirectPeerIpv4(directPeerIpv4)) return sdp
            val replacement = checkNotNull(directPeerIpv4)
            return runCatching {
                val udpHostCandidates = UDP_HOST_CANDIDATE.findAll(sdp).toList()
                if (
                    udpHostCandidates.any { candidate ->
                        isNumericIpv4Address(candidate.groups[2]?.value)
                    }
                ) {
                    return@runCatching sdp
                }
                UDP_HOST_CANDIDATE.replace(sdp) { candidate ->
                    val advertisedAddress = candidate.groups[2]?.value.orEmpty()
                    if (!advertisedAddress.endsWith(".local", ignoreCase = true)) {
                        candidate.value
                    } else {
                        candidate.groups[1]!!.value + replacement + candidate.groups[3]!!.value
                    }
                }
            }.getOrDefault(sdp)
        }

        /**
         * Recovers the LAN peer address hidden by browser mDNS when signaling arrives through
         * the cloud relay. The app's LAN STUN response yields a private srflx candidate with the
         * same UDP port as its corresponding host candidate. Requiring one private address and
         * rejecting unrelated srflx ports avoids guessing across VPNs, interfaces, or NATs while
         * allowing Chrome to omit a srflx candidate for one of several bundled transports.
         */
        internal fun inferDirectPeerIpv4FromPrivateSrflx(sdp: String): String? {
            val mdnsHostPorts = mutableSetOf<Int>()
            val privateSrflxAddresses = mutableSetOf<String>()
            val privateSrflxAddressesByPort = mutableMapOf<Int, MutableSet<String>>()

            sdp.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("a=candidate:", ignoreCase = true)) return@forEach
                val fields = line.split(Regex("[ \\t]+"))
                if (fields.size < MIN_ICE_CANDIDATE_FIELDS ||
                    !fields[ICE_PROTOCOL_FIELD].equals("udp", ignoreCase = true)
                ) {
                    return@forEach
                }
                val address = fields[ICE_ADDRESS_FIELD]
                val port = fields[ICE_PORT_FIELD].toIntOrNull()
                    ?.takeIf { it in MIN_UDP_PORT..MAX_UDP_PORT }
                    ?: return@forEach
                val typeMarker = fields.indexOfFirst { it.equals("typ", ignoreCase = true) }
                val candidateType = fields.getOrNull(typeMarker + 1)
                    ?.takeIf { typeMarker >= 0 }
                    ?: return@forEach

                when {
                    candidateType.equals("host", ignoreCase = true) &&
                        address.endsWith(".local", ignoreCase = true) -> mdnsHostPorts += port
                    candidateType.equals("srflx", ignoreCase = true) &&
                        isEligibleDirectPeerIpv4(address) -> {
                        privateSrflxAddresses += address
                        privateSrflxAddressesByPort.getOrPut(port) { mutableSetOf() } += address
                    }
                }
            }

            if (mdnsHostPorts.isEmpty() || privateSrflxAddresses.size != 1) return null
            val inferredAddress = privateSrflxAddresses.single()
            val privateSrflxPorts = privateSrflxAddressesByPort
                .filterValues { addresses -> addresses == setOf(inferredAddress) }
                .keys
            val srflxPortsMatchMdnsCandidates = privateSrflxPorts.isNotEmpty() &&
                privateSrflxPorts.all { it in mdnsHostPorts }
            return inferredAddress.takeIf { srflxPortsMatchMdnsCandidates }
        }

        private fun isNumericIpv4Address(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            val fields = value.split('.')
            return fields.size == IPV4_FIELD_COUNT && fields.all { field ->
                field.isNotEmpty() &&
                    field.all { it in '0'..'9' } &&
                    field.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        internal fun isEligibleDirectPeerIpv4(value: String?): Boolean {
            if (value.isNullOrEmpty()) return false
            val fields = value.split('.')
            if (fields.size != IPV4_FIELD_COUNT) return false
            val octets = fields.map { field ->
                if (field.isEmpty() || !field.all { it in '0'..'9' }) return false
                if (field.length > 1 && field.startsWith('0')) return false
                field.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
            }
            val first = octets[0]
            val second = octets[1]
            val isRfc1918Lan =
                first == IPV4_PRIVATE_10_PREFIX ||
                    (first == IPV4_PRIVATE_172_PREFIX && second in IPV4_PRIVATE_172_SECOND_RANGE) ||
                    (first == IPV4_PRIVATE_192_PREFIX && second == IPV4_PRIVATE_192_SECOND)
            return isRfc1918Lan &&
                first != IPV4_LOOPBACK_PREFIX &&
                first !in IPV4_MULTICAST_PREFIX_RANGE
        }

        private fun eligibleDirectPeerIpv4(address: Inet4Address?): String? {
            if (
                address == null ||
                address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isMulticastAddress
            ) {
                return null
            }
            return address.hostAddress?.takeIf(::isEligibleDirectPeerIpv4)
        }

        internal const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self'; img-src 'self' blob:; media-src 'self' blob:; object-src 'none'; " +
                "base-uri 'none'; frame-ancestors 'none'"

        private const val MAX_FRAME_VERSION_DIGITS = 19
        private const val PROJECTION_SNAPSHOT_ATTEMPTS = 3
        private const val SDP_CONTENT_TYPE = "application/sdp"
        private const val WEBRTC_SESSION_PATH_PREFIX = "/api/webrtc/session/"
        private const val DEVICE_SEEN_WRITE_INTERVAL_MILLIS = 60_000L
        private const val MAX_TRACKED_SEEN_DEVICES = 64
        private const val MAX_JAVASCRIPT_SAFE_SEQUENCE = 9_007_199_254_740_991L
        private const val MIN_WEBRTC_SESSION_ID_LENGTH = 16
        private const val MAX_WEBRTC_SESSION_ID_LENGTH = 64
        private const val MAX_WEBRTC_SDP_CHARS = 128 * 1024
        private const val MICROPHONE_CONTENT_TYPE = "text/plain"
        private const val MICROPHONE_CODEC = "pcm-s16le"
        private const val MAX_MICROPHONE_PCM_BYTES = 32 * 1024
        private const val MAX_MICROPHONE_BASE64_BYTES = 43_692
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val CHUNKED_END = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        private const val IPV4_FIELD_COUNT = 4
        private const val IPV4_LOOPBACK_PREFIX = 127
        private const val IPV4_PRIVATE_10_PREFIX = 10
        private const val IPV4_PRIVATE_172_PREFIX = 172
        private val IPV4_PRIVATE_172_SECOND_RANGE = 16..31
        private const val IPV4_PRIVATE_192_PREFIX = 192
        private const val IPV4_PRIVATE_192_SECOND = 168
        private val IPV4_MULTICAST_PREFIX_RANGE = 224..239
        private const val MIN_ICE_CANDIDATE_FIELDS = 8
        private const val ICE_PROTOCOL_FIELD = 2
        private const val ICE_ADDRESS_FIELD = 4
        private const val ICE_PORT_FIELD = 5
        private const val MIN_UDP_PORT = 1
        private const val MAX_UDP_PORT = 65_535
        private val UDP_HOST_CANDIDATE = Regex(
            pattern =
                "^(a=candidate:[^ \\t\\r\\n]+[ \\t]+\\d+[ \\t]+udp[ \\t]+\\d+[ \\t]+)" +
                    "([^ \\t\\r\\n]+)" +
                    "([ \\t]+\\d+[ \\t]+typ[ \\t]+host(?:[ \\t]+[^\\r\\n]*)?)$",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
    }
}

/**
 * Single side-effect boundary for live entitlement changes. A failed entitlement read fails
 * closed to the free one-session limit; policy reconciliation itself never escapes into the HTTP
 * handler as an exception.
 */
internal fun reconcileBrowserSessionPolicy(
    sessionLimitProvider: () -> Int,
    preferredMainDeviceId: () -> String?,
    reconcile: (maximumSessions: Int, preferredMainDeviceId: String?) -> Int,
): Int {
    val maximumSessions = runCatching(sessionLimitProvider)
        .getOrDefault(MAX_FREE_BROWSER_SESSIONS)
        .coerceIn(MAX_FREE_BROWSER_SESSIONS, MAX_PREMIUM_BROWSER_SESSIONS)
    val preferred = runCatching(preferredMainDeviceId).getOrNull()
    return runCatching { reconcile(maximumSessions, preferred) }.getOrDefault(0)
}

internal sealed interface BrowserCredentialAdmissionResult<out T> {
    data object Unauthorized : BrowserCredentialAdmissionResult<Nothing>
    data class Authorized<T>(val value: T) : BrowserCredentialAdmissionResult<T>
}

/**
 * Serializes the final browser-credential check with credential replacement.
 *
 * Callers may do bounded parsing before entering this gate, but the credential must be checked
 * again here and remain protected until the authenticated state mutation commits.
 */
internal fun <T> commitAuthorizedBrowserOperation(
    lock: Any,
    credentialStillValid: () -> Boolean,
    operation: () -> T,
): BrowserCredentialAdmissionResult<T> = synchronized(lock) {
    if (!credentialStillValid()) {
        BrowserCredentialAdmissionResult.Unauthorized
    } else {
        BrowserCredentialAdmissionResult.Authorized(operation())
    }
}

/**
 * Resolves the authenticated device inside the same admission lock that owns session insertion.
 * Device permission and public metadata therefore cannot be stale relative to a binder mutation
 * that also uses this lock.
 */
internal fun <A : Any, T> commitAuthenticatedBrowserOperation(
    lock: Any,
    authenticate: () -> A?,
    operation: (A) -> T,
): BrowserCredentialAdmissionResult<T> = synchronized(lock) {
    val authenticated = authenticate()
        ?: return@synchronized BrowserCredentialAdmissionResult.Unauthorized
    BrowserCredentialAdmissionResult.Authorized(operation(authenticated))
}

internal data class BrowserMicrophoneUpload(
    val format: Pcm16Format,
    val pcm16LittleEndian: ByteArray,
)
