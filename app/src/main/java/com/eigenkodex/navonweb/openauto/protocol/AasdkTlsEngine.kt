/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * TLS sequencing derived from f1xpl/aasdk at
 * 046b3b381595509d0939fa84b14a90978f46ff63. During authentication the TLS
 * records are carried in plain control-channel SSL_HANDSHAKE messages. Only
 * application messages sent after the handshake use the encrypted frame bit.
 */
package com.eigenkodex.navonweb.openauto.protocol

import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredential
import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredentialSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

internal enum class AasdkTlsBuildBoundary {
    DEBUG,
    RELEASE,
}

/**
 * Peer-authentication policy for the phone-side TLS server.
 *
 * AASDK development Head Unit Server certificates are not pinned by the app.
 * Callers may use platform trust or the protocol-compatible unverified mode.
 */
internal sealed interface AasdkTlsPeerTrustPolicy {
    data object PlatformTrust : AasdkTlsPeerTrustPolicy

    data object UnverifiedPeer : AasdkTlsPeerTrustPolicy
}

internal class AasdkTlsPolicyException(message: String) : IllegalArgumentException(message)

/**
 * Builds a client-mode [SSLEngine] without exporting the identity key.
 *
 * [AasdkCredentialKeyManager] returns the original [java.security.PrivateKey]
 * object to JSSE. Android Keystore-backed keys therefore remain non-exportable
 * and signing is delegated to the Keystore provider. The same path also works
 * for a development PEM credential.
 */
internal object AasdkTlsEngineFactory {
    fun createClient(
        credential: HeadUnitCredential,
        buildBoundary: AasdkTlsBuildBoundary,
        peerTrustPolicy: AasdkTlsPeerTrustPolicy,
        secureRandom: SecureRandom = SecureRandom(),
    ): SSLEngine {
        validateBoundary(credential, buildBoundary, peerTrustPolicy)

        val context = SSLContext.getInstance(TLS_PROTOCOL)
        context.init(
            arrayOf<KeyManager>(AasdkCredentialKeyManager(credential)),
            arrayOf<TrustManager>(trustManager(peerTrustPolicy)),
            secureRandom,
        )
        return context.createSSLEngine().apply {
            useClientMode = true
            val tls12 = supportedProtocols.firstOrNull { it == TLS_PROTOCOL }
                ?: throw AasdkTlsPolicyException("TLS 1.2 is not supported by this runtime")
            enabledProtocols = arrayOf(tls12)
        }
    }

    internal fun validateBoundary(
        credential: HeadUnitCredential,
        buildBoundary: AasdkTlsBuildBoundary,
        peerTrustPolicy: AasdkTlsPeerTrustPolicy,
    ) {
        if (
            buildBoundary == AasdkTlsBuildBoundary.RELEASE &&
            credential.source == HeadUnitCredentialSource.DEVELOPMENT_PEM
        ) {
            throw AasdkTlsPolicyException("development credentials are forbidden in release")
        }
    }

    private fun trustManager(policy: AasdkTlsPeerTrustPolicy): X509TrustManager = when (policy) {
        AasdkTlsPeerTrustPolicy.PlatformTrust -> platformTrustManager()
        AasdkTlsPeerTrustPolicy.UnverifiedPeer -> UnverifiedTrustManager
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: throw AasdkTlsPolicyException("platform X.509 trust manager is unavailable")
    }

    private const val TLS_PROTOCOL = "TLSv1.2"
}

internal class AasdkCredentialKeyManager(
    private val credential: HeadUnitCredential,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (matches(keyType, issuers)) arrayOf(KEY_ALIAS) else null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: java.net.Socket?,
    ): String? = keyType?.firstOrNull { matches(it, issuers) }?.let { KEY_ALIAS }

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = keyType?.firstOrNull { matches(it, issuers) }?.let { KEY_ALIAS }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == KEY_ALIAS) credential.certificateChain.toTypedArray() else null

    override fun getPrivateKey(alias: String?): java.security.PrivateKey? =
        if (alias == KEY_ALIAS) credential.privateKey else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: java.net.Socket?,
    ): String? = null

    private fun matches(keyType: String?, issuers: Array<out Principal>?): Boolean {
        if (keyType.isNullOrBlank()) return false
        if (!keyType.equals(credential.privateKey.algorithm, ignoreCase = true)) return false
        if (issuers.isNullOrEmpty()) return true
        return credential.certificateChain.any { certificate ->
            issuers.any { issuer ->
                issuer == certificate.issuerX500Principal || issuer == certificate.subjectX500Principal
            }
        }
    }

    private companion object {
        const val KEY_ALIAS = "aasdk_head_unit_identity"
    }
}

private object UnverifiedTrustManager : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal enum class AasdkTlsState {
    NEW,
    HANDSHAKING,
    ACTIVE,
    CLOSING,
    CLOSED,
}

internal data class AasdkTlsHandshakeStep(
    val state: AasdkTlsState,
    val outboundRecords: ByteArray,
    val needsPeerData: Boolean,
)

internal data class AasdkTlsUnwrapResult(
    val plaintext: ByteArray,
    val needsMoreNetworkData: Boolean,
    val state: AasdkTlsState,
)

internal class AasdkTlsEngineException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Stateful, transport-neutral [SSLEngine] driver.
 *
 * It deliberately has no socket ownership. The caller moves the emitted TLS
 * records through [AasdkTlsHandshakeWireCodec] while handshaking, then through
 * [AasdkTlsMessageLayer] for encrypted AASDK application frames.
 */
internal class AasdkTlsEngineSession(
    private val engine: SSLEngine,
    private val maximumBufferSize: Int = DEFAULT_MAXIMUM_BUFFER_SIZE,
) : AutoCloseable {
    var state: AasdkTlsState = AasdkTlsState.NEW
        private set

    private var inboundNetwork = ByteBuffer.allocate(initialPacketCapacity())

    // Steady-state wrap/unwrap scratch. Callers already serialize engine access (see the class
    // contract), so reusing one buffer per direction removes a packet-sized allocation from every
    // application record without changing any visible behavior. Results are still copied out.
    private var packetScratch: ByteBuffer? = null
    private var applicationScratch: ByteBuffer? = null

    fun beginHandshake(): AasdkTlsHandshakeStep {
        check(state == AasdkTlsState.NEW) { "TLS handshake already started" }
        try {
            engine.beginHandshake()
            state = AasdkTlsState.HANDSHAKING
            return driveHandshake()
        } catch (error: SSLException) {
            failAndClose()
            throw AasdkTlsEngineException("TLS handshake start failed", error)
        }
    }

    fun receiveHandshakeRecords(records: ByteArray): AasdkTlsHandshakeStep {
        check(state == AasdkTlsState.HANDSHAKING) { "TLS handshake is not awaiting peer records" }
        appendInbound(records)
        return try {
            driveHandshake()
        } catch (error: SSLException) {
            failAndClose()
            throw AasdkTlsEngineException("TLS handshake failed", error)
        }
    }

    /** Wraps at most one upstream AASDK plaintext fragment (0x4000 bytes). */
    fun wrapApplicationData(plaintext: ByteArray): ByteArray {
        requireActive()
        if (plaintext.size > AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD) {
            throw AasdkTlsEngineException("plaintext fragment exceeds AASDK TLS limit")
        }

        val source = ByteBuffer.wrap(plaintext)
        var output = takeScratch(::packetScratch, ::initialPacketCapacity)
        var attemptedEmptyWrap = false
        try {
            while (source.hasRemaining() || (!attemptedEmptyWrap && plaintext.isEmpty())) {
                attemptedEmptyWrap = true
                val result = engine.wrap(source, output)
                when (result.status) {
                    SSLEngineResult.Status.OK -> Unit
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        output = grow(output, "TLS packet")
                        continue
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                        throw AasdkTlsEngineException("SSLEngine wrap returned BUFFER_UNDERFLOW")
                    SSLEngineResult.Status.CLOSED -> {
                        state = AasdkTlsState.CLOSED
                        throw AasdkTlsEngineException("TLS engine closed while wrapping data")
                    }
                }
                rejectRenegotiation(result.handshakeStatus)
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0 && source.hasRemaining()) {
                    throw AasdkTlsEngineException("SSLEngine wrap made no progress")
                }
            }
        } catch (error: SSLException) {
            failAndClose()
            throw AasdkTlsEngineException("TLS application wrap failed", error)
        }
        val wrapped = output.toByteArray()
        packetScratch = output
        return wrapped
    }

    fun unwrapApplicationData(records: ByteArray): AasdkTlsUnwrapResult {
        requireActive()
        appendInbound(records)
        var plaintext = takeScratch(::applicationScratch, ::initialApplicationCapacity)
        var needsMore = false
        try {
            while (inboundNetwork.position() > 0) {
                inboundNetwork.flip()
                val result = engine.unwrap(inboundNetwork, plaintext)
                inboundNetwork.compact()
                when (result.status) {
                    SSLEngineResult.Status.OK -> Unit
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        plaintext = grow(plaintext, "TLS application")
                        continue
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        needsMore = true
                        break
                    }
                    SSLEngineResult.Status.CLOSED -> {
                        state = if (engine.isOutboundDone) AasdkTlsState.CLOSED else AasdkTlsState.CLOSING
                        break
                    }
                }
                rejectRenegotiation(result.handshakeStatus)
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) {
                    throw AasdkTlsEngineException("SSLEngine unwrap made no progress")
                }
            }
        } catch (error: SSLException) {
            failAndClose()
            throw AasdkTlsEngineException("TLS application unwrap failed", error)
        }
        val unwrapped = plaintext.toByteArray()
        applicationScratch = plaintext
        return AasdkTlsUnwrapResult(
            plaintext = unwrapped,
            needsMoreNetworkData = needsMore,
            state = state,
        )
    }

    /** Produces close_notify records. Inbound closure can complete later. */
    fun closeOutbound(): ByteArray {
        if (state == AasdkTlsState.CLOSED || state == AasdkTlsState.NEW) {
            close()
            return ByteArray(0)
        }
        engine.closeOutbound()
        state = AasdkTlsState.CLOSING
        val output = ByteArrayOutputStream()
        try {
            var iterations = 0
            while (!engine.isOutboundDone || engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                if (++iterations > MAX_ENGINE_ITERATIONS) {
                    throw AasdkTlsEngineException("TLS close exceeded progress limit")
                }
                var packet = ByteBuffer.allocate(initialPacketCapacity())
                while (true) {
                    val result = engine.wrap(EMPTY_BUFFER.duplicate(), packet)
                    if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        packet = grow(packet, "TLS close packet")
                        continue
                    }
                    output.write(packet.toByteArray())
                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        throw AasdkTlsEngineException("SSLEngine close wrap returned BUFFER_UNDERFLOW")
                    }
                    break
                }
                if (engine.isOutboundDone) break
            }
        } catch (error: SSLException) {
            failAndClose()
            throw AasdkTlsEngineException("TLS close failed", error)
        }
        if (engine.isInboundDone) state = AasdkTlsState.CLOSED
        return output.toByteArray()
    }

    override fun close() {
        engine.closeOutbound()
        if (!engine.isInboundDone) {
            try {
                engine.closeInbound()
            } catch (_: SSLException) {
                // Local teardown may occur without a peer close_notify.
            }
        }
        inboundNetwork.clear()
        while (inboundNetwork.hasRemaining()) inboundNetwork.put(0)
        inboundNetwork.clear()
        // The reusable wrap/unwrap scratches hold the most recent record's bytes (the unwrap
        // side in plaintext); scrub and drop them with the same discipline as inboundNetwork.
        packetScratch = scrubAndDropScratch(packetScratch)
        applicationScratch = scrubAndDropScratch(applicationScratch)
        state = AasdkTlsState.CLOSED
    }

    private fun scrubAndDropScratch(buffer: ByteBuffer?): ByteBuffer? {
        if (buffer != null) {
            buffer.clear()
            while (buffer.hasRemaining()) buffer.put(0)
        }
        return null
    }

    private fun driveHandshake(): AasdkTlsHandshakeStep {
        val outbound = ByteArrayOutputStream()
        var iterations = 0
        while (state == AasdkTlsState.HANDSHAKING) {
            if (++iterations > MAX_ENGINE_ITERATIONS) {
                throw AasdkTlsEngineException("TLS handshake exceeded progress limit")
            }
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runDelegatedTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val result = wrapHandshakeRecord(outbound)
                    processHandshakeResult(result)
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (inboundNetwork.position() == 0) {
                        break
                    }
                    val result = unwrapHandshakeRecord()
                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
                    // Some Android SSLEngine providers report an incomplete
                    // record as OK/NEED_UNWRAP without consuming or producing
                    // bytes. Preserve the pending input and wait for the next
                    // AASDK handshake message instead of spinning on it.
                    if (
                        result.status == SSLEngineResult.Status.OK &&
                        result.bytesConsumed() == 0 &&
                        result.bytesProduced() == 0 &&
                        result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP
                    ) {
                        break
                    }
                    processHandshakeResult(result)
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                -> state = AasdkTlsState.ACTIVE
            }
        }
        return AasdkTlsHandshakeStep(
            state = state,
            outboundRecords = outbound.toByteArray(),
            needsPeerData = state == AasdkTlsState.HANDSHAKING &&
                engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
        )
    }

    private fun wrapHandshakeRecord(output: ByteArrayOutputStream): SSLEngineResult {
        var packet = ByteBuffer.allocate(initialPacketCapacity())
        while (true) {
            val result = engine.wrap(EMPTY_BUFFER.duplicate(), packet)
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                packet = grow(packet, "TLS handshake packet")
                continue
            }
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                throw AasdkTlsEngineException("SSLEngine handshake wrap returned BUFFER_UNDERFLOW")
            }
            output.write(packet.toByteArray())
            return result
        }
    }

    private fun unwrapHandshakeRecord(): SSLEngineResult {
        var application = ByteBuffer.allocate(initialApplicationCapacity())
        while (true) {
            inboundNetwork.flip()
            val result = engine.unwrap(inboundNetwork, application)
            inboundNetwork.compact()
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                application = grow(application, "TLS handshake application")
                continue
            }
            if (application.position() != 0) {
                throw AasdkTlsEngineException("unexpected application data during TLS handshake")
            }
            return result
        }
    }

    private fun processHandshakeResult(result: SSLEngineResult) {
        when (result.status) {
            SSLEngineResult.Status.OK -> Unit
            SSLEngineResult.Status.BUFFER_OVERFLOW,
            SSLEngineResult.Status.BUFFER_UNDERFLOW,
            -> return
            SSLEngineResult.Status.CLOSED -> {
                state = AasdkTlsState.CLOSED
                return
            }
        }
        if (
            result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED ||
            result.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            state = AasdkTlsState.ACTIVE
        }
        if (result.bytesConsumed() == 0 && result.bytesProduced() == 0 && state == AasdkTlsState.HANDSHAKING) {
            val status = result.handshakeStatus
            if (status != SSLEngineResult.HandshakeStatus.NEED_TASK &&
                status != SSLEngineResult.HandshakeStatus.NEED_UNWRAP
            ) {
                throw AasdkTlsEngineException("SSLEngine handshake made no progress")
            }
        }
    }

    private fun runDelegatedTasks() {
        var count = 0
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
            count += 1
        }
        if (count == 0) throw AasdkTlsEngineException("SSLEngine requested a missing delegated task")
    }

    private fun appendInbound(records: ByteArray) {
        if (records.isEmpty()) return
        if (records.size > maximumBufferSize - inboundNetwork.position()) {
            throw AasdkTlsEngineException("pending TLS input exceeds limit")
        }
        if (inboundNetwork.remaining() < records.size) {
            inboundNetwork = growToFit(inboundNetwork, inboundNetwork.position() + records.size, "TLS input")
        }
        inboundNetwork.put(records)
    }

    private fun grow(buffer: ByteBuffer, label: String): ByteBuffer {
        val required = if (buffer.capacity() == 0) 1 else buffer.capacity() + 1
        return growToFit(buffer, required, label)
    }

    private fun growToFit(buffer: ByteBuffer, required: Int, label: String): ByteBuffer {
        var capacity = maxOf(1, buffer.capacity())
        while (capacity < required && capacity < maximumBufferSize) {
            capacity = minOf(maximumBufferSize, capacity * 2)
        }
        if (capacity < required || capacity <= buffer.capacity()) {
            throw AasdkTlsEngineException("$label buffer exceeds limit")
        }
        val replacement = ByteBuffer.allocate(capacity)
        buffer.flip()
        replacement.put(buffer)
        return replacement
    }

    private fun takeScratch(
        slot: kotlin.reflect.KMutableProperty0<ByteBuffer?>,
        initialCapacity: () -> Int,
    ): ByteBuffer {
        val buffer = slot.get() ?: ByteBuffer.allocate(initialCapacity())
        slot.set(null)
        buffer.clear()
        return buffer
    }

    private fun initialPacketCapacity(): Int =
        engine.session.packetBufferSize.coerceIn(1, maximumBufferSize)

    private fun initialApplicationCapacity(): Int =
        engine.session.applicationBufferSize.coerceIn(1, maximumBufferSize)

    private fun requireActive() {
        if (state != AasdkTlsState.ACTIVE) throw AasdkTlsEngineException("TLS session is not active")
    }

    private fun rejectRenegotiation(status: SSLEngineResult.HandshakeStatus) {
        if (status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
            status != SSLEngineResult.HandshakeStatus.FINISHED
        ) {
            throw AasdkTlsEngineException("TLS renegotiation is not supported")
        }
    }

    private fun failAndClose() {
        try {
            close()
        } catch (_: Exception) {
            state = AasdkTlsState.CLOSED
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = ByteArray(position())
        flip()
        get(copy)
        return copy
    }

    private companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0).asReadOnlyBuffer()
        const val DEFAULT_MAXIMUM_BUFFER_SIZE = 1024 * 1024
        const val MAX_ENGINE_ITERATIONS = 1_024
    }
}

/** AASDK plain control-message envelope used only during the TLS handshake. */
internal object AasdkTlsHandshakeWireCodec {
    private const val SSL_HANDSHAKE_MESSAGE_ID = 0x0003

    fun encode(records: ByteArray): List<ByteArray> {
        if (records.isEmpty()) return emptyList()
        val payload = ByteArray(2 + records.size)
        payload[0] = (SSL_HANDSHAKE_MESSAGE_ID ushr 8).toByte()
        payload[1] = SSL_HANDSHAKE_MESSAGE_ID.toByte()
        records.copyInto(payload, 2)
        return AasdkFrameCodec.encodePlainMessage(
            channelId = AasdkFrameCodec.CONTROL_CHANNEL,
            controlMessage = false,
            payload = payload,
        )
    }

    fun decode(message: AasdkPlainMessage): ByteArray {
        if (message.channelId != AasdkFrameCodec.CONTROL_CHANNEL || message.controlMessage) {
            throw AasdkProtocolException("TLS handshake must use the specific control channel")
        }
        if (message.payload.size < 3 || readUInt16(message.payload, 0) != SSL_HANDSHAKE_MESSAGE_ID) {
            throw AasdkProtocolException("not an AASDK SSL_HANDSHAKE message")
        }
        return message.payload.copyOfRange(2, message.payload.size)
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}

internal data class AasdkTlsHandshakeExchange(
    val state: AasdkTlsState,
    /**
     * Must be written, in order, before an ACTIVE result is followed by the
     * separate plain AuthComplete message. ACTIVE can legitimately coincide
     * with a final outbound TLS flight.
     */
    val outboundFrames: List<ByteArray>,
    val needsPeerData: Boolean,
)

/**
 * Binds the engine handshake to the upstream AASDK control-channel sequence.
 * AuthComplete intentionally remains outside this TLS class. The orchestrator
 * must finish writing every [AasdkTlsHandshakeExchange.outboundFrames] item
 * before sending that separate plain control message.
 */
internal class AasdkTlsHandshakeChannel(
    private val tls: AasdkTlsEngineSession,
    private val assembler: AasdkPlainMessageAssembler = AasdkPlainMessageAssembler(),
) {
    fun begin(): AasdkTlsHandshakeExchange = tls.beginHandshake().toExchange()

    fun acceptFrame(frameBytes: ByteArray): AasdkTlsHandshakeExchange {
        val frame = AasdkFrameCodec.decodeFrame(frameBytes)
        if (frame.encrypted) throw AasdkProtocolException("TLS handshake frame must be plain")
        val message = assembler.accept(frame)
            ?: return AasdkTlsHandshakeExchange(
                state = tls.state,
                outboundFrames = emptyList(),
                needsPeerData = true,
            )
        val records = AasdkTlsHandshakeWireCodec.decode(message)
        return tls.receiveHandshakeRecords(records).toExchange()
    }

    private fun AasdkTlsHandshakeStep.toExchange(): AasdkTlsHandshakeExchange =
        AasdkTlsHandshakeExchange(
            state = state,
            outboundFrames = AasdkTlsHandshakeWireCodec.encode(outboundRecords),
            needsPeerData = needsPeerData,
        )
}

/** Encrypts after the handshake using the same per-fragment order as AASDK. */
internal class AasdkTlsMessageLayer(
    private val tls: AasdkTlsEngineSession,
) {
    fun encodeEncryptedMessage(
        channelId: Int,
        controlMessage: Boolean,
        plaintext: ByteArray,
    ): List<ByteArray> {
        if (plaintext.size > AasdkFrameCodec.MAX_MESSAGE_SIZE) {
            throw AasdkProtocolException("message exceeds limit")
        }
        if (plaintext.size <= AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD) {
            val ciphertext = tls.wrapApplicationData(plaintext)
            return listOf(
                AasdkFrameCodec.encodeEncryptedFrame(
                    channelId = channelId,
                    type = AasdkFrameType.BULK,
                    controlMessage = controlMessage,
                    ciphertextPayload = ciphertext,
                ),
            )
        }

        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < plaintext.size) {
            val end = minOf(offset + AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD, plaintext.size)
            val type = when {
                offset == 0 -> AasdkFrameType.FIRST
                end == plaintext.size -> AasdkFrameType.LAST
                else -> AasdkFrameType.MIDDLE
            }
            val ciphertext = tls.wrapApplicationData(plaintext.copyOfRange(offset, end))
            frames += AasdkFrameCodec.encodeEncryptedFrame(
                channelId = channelId,
                type = type,
                controlMessage = controlMessage,
                ciphertextPayload = ciphertext,
                declaredPlaintextTotalSize = if (type == AasdkFrameType.FIRST) plaintext.size else null,
            )
            offset = end
        }
        return frames
    }

    /** Returns a plaintext frame ready for [AasdkPlainMessageAssembler]. */
    fun unwrapEncryptedFrame(frame: AasdkFrame): AasdkFrame {
        if (!frame.encrypted) throw AasdkProtocolException("frame is not encrypted")
        val result = tls.unwrapApplicationData(frame.payload)
        if (result.needsMoreNetworkData) {
            throw AasdkProtocolException("encrypted frame contains an incomplete TLS record")
        }
        return frame.copy(encrypted = false, payload = result.plaintext)
    }
}
