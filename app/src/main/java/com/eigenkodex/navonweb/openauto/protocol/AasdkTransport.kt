/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.protocol

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface AasdkByteTransport : Closeable {
    suspend fun write(data: ByteArray)
    suspend fun readExactly(size: Int): ByteArray
}

internal class AasdkTcpTransport(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : AasdkByteTransport {
    private var socket: Socket? = null

    // Wrapped once at connect so the frame reader's small header reads do not each become a
    // socket syscall. close() on the socket still interrupts a read blocked in the buffer fill.
    private var input: InputStream? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (host.isBlank()) throw IllegalArgumentException("host is blank")
        if (port !in 1..65535) throw IllegalArgumentException("invalid port")
        if (socket != null) throw IllegalStateException("transport already connected")
        val candidate = Socket()
        try {
            candidate.soTimeout = readTimeoutMillis
            candidate.tcpNoDelay = true
            candidate.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            input = BufferedInputStream(candidate.getInputStream(), READ_BUFFER_BYTES)
            socket = candidate
        } catch (error: Exception) {
            candidate.close()
            throw error
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val active = socket ?: throw IllegalStateException("transport is not connected")
        val output = active.getOutputStream()
        output.write(data)
        output.flush()
        Unit
    }

    override suspend fun readExactly(size: Int): ByteArray = withContext(Dispatchers.IO) {
        if (size < 0 || size > AasdkFrameCodec.MAX_MESSAGE_SIZE) {
            throw AasdkProtocolException("invalid read size")
        }
        if (socket == null) throw IllegalStateException("transport is not connected")
        val source = input ?: throw IllegalStateException("transport is not connected")
        val output = ByteArray(size)
        var offset = 0
        while (offset < output.size) {
            val count = source.read(output, offset, output.size - offset)
            if (count < 0) throw EOFException("AASDK transport closed")
            if (count == 0) continue
            offset += count
        }
        output
    }

    override fun close() {
        socket?.close()
        socket = null
        input = null
    }

    companion object {
        const val DEFAULT_PORT = 5277
        // Large enough to coalesce one 0xFFFF wire frame's header and most of its payload into
        // a single socket read without holding a per-connection buffer worth noting.
        private const val READ_BUFFER_BYTES = 64 * 1024
        // Keep socket idleness above the application ping watchdog (35 s), so
        // the protocol-level bounded-ping diagnostic remains authoritative.
        // close() still interrupts a blocked read immediately.
        const val DEFAULT_READ_TIMEOUT_MILLIS = 45_000
    }
}

internal object AasdkFrameStream {
    private const val PREFIX_SIZE = 4
    private const val EXTENDED_SIZE_FIELD = 4

    suspend fun readFrame(transport: AasdkByteTransport): ByteArray {
        // Header and short size are always adjacent; one read avoids per-field syscalls and the
        // chained array concatenations the previous field-at-a-time reads required.
        val prefix = transport.readExactly(PREFIX_SIZE)
        val first = (prefix[1].toInt() and 0x03) == AasdkFrameType.FIRST.bits
        val payloadSize =
            ((prefix[2].toInt() and 0xFF) shl 8) or
                (prefix[3].toInt() and 0xFF)
        val extendedSize = if (first) transport.readExactly(EXTENDED_SIZE_FIELD) else null
        val payload = transport.readExactly(payloadSize)

        val frame = ByteArray(PREFIX_SIZE + (extendedSize?.size ?: 0) + payload.size)
        prefix.copyInto(frame)
        var offset = PREFIX_SIZE
        if (extendedSize != null) {
            extendedSize.copyInto(frame, offset)
            offset += extendedSize.size
        }
        payload.copyInto(frame, offset)
        return frame
    }
}

internal object AasdkPlainMessageStream {
    suspend fun readMessage(transport: AasdkByteTransport): AasdkPlainMessage {
        val assembler = AasdkPlainMessageAssembler()
        while (true) {
            val frame = AasdkFrameCodec.decodeFrame(AasdkFrameStream.readFrame(transport))
            val message = assembler.accept(frame)
            if (message != null) return message
        }
    }
}

internal class AasdkVersionNegotiator {
    suspend fun negotiate(transport: AasdkByteTransport): AasdkVersionResponse {
        transport.write(AasdkBootstrapCodec.versionRequest())
        return AasdkBootstrapCodec.parseVersionResponse(AasdkFrameStream.readFrame(transport))
    }
}
