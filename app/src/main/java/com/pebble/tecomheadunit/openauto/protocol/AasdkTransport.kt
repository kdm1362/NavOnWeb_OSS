/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import java.io.Closeable
import java.io.EOFException
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

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (host.isBlank()) throw IllegalArgumentException("host is blank")
        if (port !in 1..65535) throw IllegalArgumentException("invalid port")
        if (socket != null) throw IllegalStateException("transport already connected")
        val candidate = Socket()
        try {
            candidate.soTimeout = readTimeoutMillis
            candidate.tcpNoDelay = true
            candidate.connect(InetSocketAddress(host, port), connectTimeoutMillis)
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
        val active = socket ?: throw IllegalStateException("transport is not connected")
        val output = ByteArray(size)
        var offset = 0
        val input = active.getInputStream()
        while (offset < output.size) {
            val count = input.read(output, offset, output.size - offset)
            if (count < 0) throw EOFException("AASDK transport closed")
            if (count == 0) continue
            offset += count
        }
        output
    }

    override fun close() {
        socket?.close()
        socket = null
    }

    companion object {
        const val DEFAULT_PORT = 5277
        // Keep socket idleness above the application ping watchdog (35 s), so
        // the protocol-level bounded-ping diagnostic remains authoritative.
        // close() still interrupts a blocked read immediately.
        const val DEFAULT_READ_TIMEOUT_MILLIS = 45_000
    }
}

internal object AasdkFrameStream {
    suspend fun readFrame(transport: AasdkByteTransport): ByteArray {
        val header = transport.readExactly(2)
        val first = (header[1].toInt() and 0x03) == AasdkFrameType.FIRST.bits
        val shortSize = transport.readExactly(2)
        val payloadSize =
            ((shortSize[0].toInt() and 0xFF) shl 8) or
                (shortSize[1].toInt() and 0xFF)
        val extendedSize = if (first) transport.readExactly(4) else ByteArray(0)
        val payload = transport.readExactly(payloadSize)
        return header + shortSize + extendedSize + payload
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
