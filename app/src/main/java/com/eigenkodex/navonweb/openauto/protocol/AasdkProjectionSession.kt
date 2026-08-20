/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.protocol

import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredential
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An authenticated AASDK application message.
 *
 * [payload] contains the two-byte AASDK message id followed by that message's
 * encoded body. Keeping the envelope intact lets the service/channel codecs
 * remain independent of transport and TLS ownership.
 */
internal data class AasdkSessionMessage(
    val channelId: Int,
    val controlMessage: Boolean,
    val encrypted: Boolean,
    val payload: ByteArray,
)

/**
 * The successfully opened session and the request which starts service setup.
 * Ownership of [session] passes to the caller and must be closed explicitly.
 */
internal data class AasdkProjectionSessionOpenResult(
    val session: AasdkProjectionSession,
    val serviceDiscoveryRequest: AasdkSessionMessage,
    val peerMajorVersion: Int,
    val peerMinorVersion: Int,
)

internal class AasdkVersionMismatchException(
    val peerMajorVersion: Int,
    val peerMinorVersion: Int,
) : IllegalStateException("AASDK peer version is not compatible")

/** Small seam which keeps [AasdkProjectionSession] independently testable. */
internal interface AasdkSessionCipher : Closeable {
    fun encode(
        channelId: Int,
        controlMessage: Boolean,
        plaintext: ByteArray,
    ): List<ByteArray>

    fun decode(frame: AasdkFrame): AasdkFrame
}

private class AasdkTlsSessionCipher(
    private val tls: AasdkTlsEngineSession,
) : AasdkSessionCipher {
    private val layer = AasdkTlsMessageLayer(tls)

    override fun encode(
        channelId: Int,
        controlMessage: Boolean,
        plaintext: ByteArray,
    ): List<ByteArray> = layer.encodeEncryptedMessage(channelId, controlMessage, plaintext)

    override fun decode(frame: AasdkFrame): AasdkFrame = layer.unwrapEncryptedFrame(frame)

    override fun close() = tls.close()
}

/**
 * Owns an authenticated transport until explicitly closed.
 *
 * At most one transport read and one transport write can be active. TLS calls
 * additionally share [cipherLock], because callers may receive and send from
 * different coroutines while SSLEngine state itself is not generally safe for
 * unsynchronised access. A cancelled or failed operation closes the whole
 * session: continuing after a partially consumed or written frame would lose
 * protocol framing.
 */
internal class AasdkProjectionSession internal constructor(
    private val transport: AasdkByteTransport,
    private val cipher: AasdkSessionCipher,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val cipherLock = Any()
    private val fragmentStateLock = Any()
    private val fragmentedMessages = mutableMapOf<MessageStreamKey, FragmentedMessageState>()
    private var bufferedFragmentBytes = 0

    val isOpen: Boolean
        get() = !closed.get()

    suspend fun receiveMessage(): AasdkSessionMessage = readMutex.withLock {
        ensureOpen()
        try {
            var frameCount = 0
            while (true) {
                frameCount += 1
                if (frameCount > MAX_FRAMES_PER_MESSAGE) {
                    throw AasdkProtocolException("encrypted message frame limit exceeded")
                }

                val wireFrame = AasdkFrameCodec.decodeFrame(AasdkFrameStream.readFrame(transport))
                validatePostAuthenticationProtection(wireFrame)
                val plaintext = if (wireFrame.encrypted) {
                    synchronized(cipherLock) {
                        ensureOpen()
                        cipher.decode(wireFrame)
                    }
                } else {
                    wireFrame
                }
                ensureOpen()
                val message = assembleFrame(wireFrame, plaintext) ?: continue
                return@withLock AasdkSessionMessage(
                    channelId = message.channelId,
                    controlMessage = message.controlMessage,
                    encrypted = wireFrame.encrypted,
                    payload = message.payload,
                )
            }
            @Suppress("UNREACHABLE_CODE")
            throw AssertionError("unreachable")
        } catch (error: CancellationException) {
            closeAfterFailure()
            throw error
        } catch (error: Throwable) {
            closeAfterFailure()
            throw error
        }
    }

    suspend fun sendMessage(
        channelId: Int,
        controlMessage: Boolean,
        payload: ByteArray,
        encrypted: Boolean = true,
    ) {
        if (channelId !in 0..255) throw AasdkProtocolException("invalid channel")
        if (payload.size > AasdkFrameCodec.MAX_MESSAGE_SIZE) {
            throw AasdkProtocolException("message exceeds limit")
        }
        if (!encrypted && (channelId != AasdkFrameCodec.CONTROL_CHANNEL || controlMessage)) {
            throw AasdkProtocolException("plain post-authentication messages must be control-specific")
        }
        // The caller may reuse or clear its buffer while this coroutine waits
        // for the writer. Session framing must observe a stable snapshot.
        val snapshot = payload.copyOf()

        writeMutex.withLock {
            ensureOpen()
            try {
                val frames = if (encrypted) {
                    synchronized(cipherLock) {
                        ensureOpen()
                        cipher.encode(channelId, controlMessage, snapshot)
                    }
                } else {
                    AasdkFrameCodec.encodePlainMessage(channelId, controlMessage, snapshot)
                }
                if (frames.isEmpty() || frames.size > MAX_FRAMES_PER_MESSAGE) {
                    throw AasdkProtocolException("invalid encrypted frame count")
                }
                for (frame in frames) {
                    ensureOpen()
                    transport.write(frame)
                }
            } catch (error: CancellationException) {
                closeAfterFailure()
                throw error
            } catch (error: Throwable) {
                closeAfterFailure()
                throw error
            } finally {
                snapshot.fill(0)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            // Closing the transport first unblocks a reader waiting in I/O.
            transport.close()
        } finally {
            try {
                clearFragmentedMessages()
            } finally {
                synchronized(cipherLock) {
                    cipher.close()
                }
            }
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw IllegalStateException("AASDK session is closed")
    }

    /** Legacy peers may still send control-specific ping traffic without session encryption. */
    private fun validatePostAuthenticationProtection(frame: AasdkFrame) {
        if (!frame.encrypted &&
            (frame.channelId != AasdkFrameCodec.CONTROL_CHANNEL || frame.controlMessage)
        ) {
            throw AasdkProtocolException("unexpected plain post-authentication frame")
        }
    }

    /**
     * Modern Android Auto may interleave a complete message from one logical
     * stream while another stream's large media message is fragmented. The
     * pinned AASDK sender serialises whole messages, so this is a deliberately
     * narrow interoperability extension: only distinct (channel, control)
     * streams may coexist. Within one stream, ordering, encryption protection,
     * size and frame-count checks remain fail-closed.
     */
    private fun assembleFrame(
        wireFrame: AasdkFrame,
        plaintextFrame: AasdkFrame,
    ): AasdkPlainMessage? = synchronized(fragmentStateLock) {
        requireUnwrappedMetadata(wireFrame, plaintextFrame)
        val key = MessageStreamKey(wireFrame.channelId, wireFrame.controlMessage)

        when (wireFrame.type) {
            AasdkFrameType.BULK -> {
                if (fragmentedMessages.containsKey(key)) {
                    throw AasdkProtocolException("bulk frame interrupted fragmented stream")
                }
                AasdkPlainMessageAssembler().accept(plaintextFrame)
            }

            AasdkFrameType.FIRST -> {
                if (fragmentedMessages.containsKey(key)) {
                    throw AasdkProtocolException("fragmented message already in progress")
                }
                if (fragmentedMessages.size >= MAX_ACTIVE_FRAGMENTED_MESSAGES) {
                    throw AasdkProtocolException("active fragmented message limit exceeded")
                }

                val state = FragmentedMessageState(
                    encrypted = wireFrame.encrypted,
                    bufferedBytes = plaintextFrame.payload.size,
                )
                val message = state.assembler.accept(plaintextFrame)
                if (message != null) {
                    throw AasdkProtocolException("first frame completed fragmented message")
                }
                reserveFragmentBytes(state.bufferedBytes)
                fragmentedMessages[key] = state
                null
            }

            AasdkFrameType.MIDDLE,
            AasdkFrameType.LAST,
            -> {
                val state = fragmentedMessages[key]
                    ?: throw AasdkProtocolException("fragment continuation without first frame")
                if (state.encrypted != wireFrame.encrypted) {
                    throw AasdkProtocolException("fragment protection mismatch")
                }
                if (state.frameCount >= MAX_FRAMES_PER_MESSAGE) {
                    throw AasdkProtocolException("encrypted message frame limit exceeded")
                }

                reserveFragmentBytes(plaintextFrame.payload.size)
                state.frameCount += 1
                state.bufferedBytes += plaintextFrame.payload.size
                val message = state.assembler.accept(plaintextFrame)
                if (message != null) {
                    fragmentedMessages.remove(key)
                    releaseFragmentBytes(state.bufferedBytes)
                }
                message
            }
        }
    }

    private fun requireUnwrappedMetadata(
        wireFrame: AasdkFrame,
        plaintextFrame: AasdkFrame,
    ) {
        if (
            plaintextFrame.encrypted ||
            plaintextFrame.channelId != wireFrame.channelId ||
            plaintextFrame.type != wireFrame.type ||
            plaintextFrame.controlMessage != wireFrame.controlMessage ||
            plaintextFrame.declaredTotalSize != wireFrame.declaredTotalSize
        ) {
            throw AasdkProtocolException("TLS unwrap changed frame metadata")
        }
    }

    private fun reserveFragmentBytes(size: Int) {
        if (size < 0 || bufferedFragmentBytes.toLong() + size > MAX_BUFFERED_FRAGMENT_BYTES) {
            throw AasdkProtocolException("fragment buffer limit exceeded")
        }
        bufferedFragmentBytes += size
    }

    private fun releaseFragmentBytes(size: Int) {
        bufferedFragmentBytes = (bufferedFragmentBytes - size).coerceAtLeast(0)
    }

    private fun clearFragmentedMessages() = synchronized(fragmentStateLock) {
        fragmentedMessages.values.forEach { it.assembler.reset() }
        fragmentedMessages.clear()
        bufferedFragmentBytes = 0
    }

    private fun closeAfterFailure() {
        runCatching { close() }
    }

    internal companion object {
        // A conforming peer uses at most 1,024 0x4000-byte fragments for the
        // 16 MiB local message limit. One extra slot allows a defensive edge
        // without permitting an unbounded stream of empty continuation frames.
        const val MAX_FRAMES_PER_MESSAGE =
            (AasdkFrameCodec.MAX_MESSAGE_SIZE / AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD) + 1

        const val MAX_ACTIVE_FRAGMENTED_MESSAGES = 32
        const val MAX_BUFFERED_FRAGMENT_BYTES = 2L * AasdkFrameCodec.MAX_MESSAGE_SIZE
    }

    private data class MessageStreamKey(
        val channelId: Int,
        val controlMessage: Boolean,
    )

    private class FragmentedMessageState(
        val encrypted: Boolean,
        var bufferedBytes: Int,
        var frameCount: Int = 1,
        val assembler: AasdkPlainMessageAssembler = AasdkPlainMessageAssembler(),
    )
}

/**
 * VERSION -> TLS 1.2 -> AuthComplete -> first ServiceDiscovery request.
 *
 * Unlike [AasdkProjectionSessionProbe], this connector transfers live
 * transport/TLS ownership to the returned [AasdkProjectionSession]. All paths
 * before that hand-off close both resources, including coroutine cancellation.
 */
internal class AasdkProjectionSessionConnector(
    private val openTransport: suspend (host: String, port: Int) -> AasdkByteTransport =
        { host, port ->
            AasdkTcpTransport(host = host, port = port).also { it.connect() }
        },
    private val createTls: (
        credential: HeadUnitCredential,
        buildBoundary: AasdkTlsBuildBoundary,
        peerTrustPolicy: AasdkTlsPeerTrustPolicy,
    ) -> AasdkTlsEngineSession = { credential, buildBoundary, peerTrustPolicy ->
        AasdkTlsEngineSession(
            AasdkTlsEngineFactory.createClient(
                credential = credential,
                buildBoundary = buildBoundary,
                peerTrustPolicy = peerTrustPolicy,
            ),
        )
    },
) {
    suspend fun open(
        host: String,
        port: Int = AasdkTcpTransport.DEFAULT_PORT,
        credential: HeadUnitCredential,
        buildBoundary: AasdkTlsBuildBoundary,
        peerTrustPolicy: AasdkTlsPeerTrustPolicy,
    ): AasdkProjectionSessionOpenResult {
        val normalizedHost = host.trim()
        require(normalizedHost.isNotEmpty()) { "host is blank" }
        require(port in 1..65535) { "invalid port" }

        var transport: AasdkByteTransport? = null
        var tls: AasdkTlsEngineSession? = null
        var session: AasdkProjectionSession? = null
        var ownershipTransferred = false
        try {
            transport = openTransport(normalizedHost, port)
            val version = AasdkVersionNegotiator().negotiate(transport)
            if (!version.matches) {
                throw AasdkVersionMismatchException(version.major, version.minor)
            }

            tls = createTls(credential, buildBoundary, peerTrustPolicy)
            var handshake = tls.beginHandshake()
            writeHandshakeFlight(transport, handshake.outboundRecords)
            var exchanges = 0
            while (handshake.state == AasdkTlsState.HANDSHAKING) {
                exchanges += 1
                if (exchanges > MAX_HANDSHAKE_EXCHANGES) {
                    throw AasdkTlsEngineException("TLS handshake exchange limit exceeded")
                }
                if (!handshake.needsPeerData && handshake.outboundRecords.isEmpty()) {
                    throw AasdkTlsEngineException("TLS handshake made no transport progress")
                }
                val incoming = AasdkPlainMessageStream.readMessage(transport)
                val records = AasdkBootstrapCodec.parseTlsHandshake(incoming)
                handshake = tls.receiveHandshakeRecords(records)
                writeHandshakeFlight(transport, handshake.outboundRecords)
            }
            if (handshake.state != AasdkTlsState.ACTIVE) {
                throw AasdkTlsEngineException("TLS peer closed during handshake")
            }

            transport.write(AasdkBootstrapCodec.authCompleteSuccess())
            session = AasdkProjectionSession(transport, AasdkTlsSessionCipher(tls))
            val discovery = session.receiveMessage()
            requireServiceDiscovery(discovery)

            ownershipTransferred = true
            return AasdkProjectionSessionOpenResult(
                session = session,
                serviceDiscoveryRequest = discovery,
                peerMajorVersion = version.major,
                peerMinorVersion = version.minor,
            )
        } catch (error: CancellationException) {
            throw error
        } finally {
            if (!ownershipTransferred) {
                if (session != null) {
                    runCatching { session.close() }
                } else {
                    runCatching { transport?.close() }
                    runCatching { tls?.close() }
                }
            }
        }
    }

    private suspend fun writeHandshakeFlight(
        transport: AasdkByteTransport,
        records: ByteArray,
    ) {
        if (records.isEmpty()) return
        AasdkBootstrapCodec.tlsHandshake(records).forEach { transport.write(it) }
    }

    private fun requireServiceDiscovery(message: AasdkSessionMessage) {
        if (
            message.channelId != AasdkFrameCodec.CONTROL_CHANNEL ||
            message.controlMessage ||
            !message.encrypted ||
            message.payload.size < 2 ||
            readUInt16(message.payload, 0) != SERVICE_DISCOVERY_REQUEST
        ) {
            throw AasdkProtocolException("expected ServiceDiscovery request after authentication")
        }
    }

    private companion object {
        const val MAX_HANDSHAKE_EXCHANGES = 32
        const val SERVICE_DISCOVERY_REQUEST = 0x0005

        fun readUInt16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}
