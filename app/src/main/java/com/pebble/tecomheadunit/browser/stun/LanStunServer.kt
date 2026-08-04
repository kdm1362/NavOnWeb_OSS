/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.stun

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Single-threaded, state-free STUN Binding responder for private IPv4 LAN clients only.
 *
 * It is intentionally not TURN: it never relays media, allocates a client session, honors an
 * alternate response address, or listens on a public service port.
 */
class LanStunServer(
    private val onFirstDatagram: (decoded: Boolean, length: Int) -> Unit = { _, _ -> },
    private val onFirstResponse: () -> Unit = {},
) : Closeable {
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val firstDatagramObserved = AtomicBoolean(false)
    private val firstResponseObserved = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    val localPort: Int
        get() = synchronized(lifecycleLock) {
            socket?.localPort?.takeIf { it > 0 }
                ?: throw IllegalStateException("LAN STUN server is not running")
        }

    /** Port zero asks the OS for an ephemeral UDP port on all local interfaces. */
    fun start(port: Int = 0): Int = synchronized(lifecycleLock) {
        check(!closed.get()) { "LAN STUN server is closed" }
        check(socket == null) { "LAN STUN server already started" }
        require(port in 0..65_535) { "invalid UDP port" }

        val createdSocket = DatagramSocket(null).apply {
            reuseAddress = false
            receiveBufferSize = RECEIVE_BUFFER_BYTES
            bind(InetSocketAddress(port))
        }
        val createdWorker = Thread(
            { receiveLoop(createdSocket) },
            "NavOnWebLanStun",
        ).apply { isDaemon = true }
        socket = createdSocket
        worker = createdWorker
        createdWorker.start()
        createdSocket.localPort
    }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val receiveBuffer = ByteArray(LanStunCodec.MAX_DATAGRAM_BYTES + 1)
        val responseLimiter = LanStunResponseRateLimiter()
        while (!closed.get() && !activeSocket.isClosed) {
            val request = DatagramPacket(receiveBuffer, receiveBuffer.size)
            try {
                activeSocket.receive(request)
            } catch (_: SocketException) {
                break
            } catch (_: Throwable) {
                continue
            }
            val sourceAddress = request.address
            if (!isAllowedClientAddress(sourceAddress)) continue
            val transactionId = LanStunCodec.decodeBindingRequest(request.data, request.length)
            if (firstDatagramObserved.compareAndSet(false, true)) {
                runCatching { onFirstDatagram(transactionId != null, request.length) }
            }
            if (transactionId == null) continue
            if (!responseLimiter.tryAcquire()) continue
            val response = LanStunCodec.encodeBindingSuccess(
                transactionId = transactionId,
                mappedAddress = sourceAddress as Inet4Address,
                mappedPort = request.port,
            )
            val sent = runCatching {
                activeSocket.send(
                    DatagramPacket(response, response.size, sourceAddress, request.port),
                )
            }.isSuccess
            if (sent && firstResponseObserved.compareAndSet(false, true)) {
                runCatching(onFirstResponse)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val resources = synchronized(lifecycleLock) {
            val result = socket to worker
            socket = null
            worker = null
            result
        }
        resources.first?.close()
        val activeWorker = resources.second
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(CLOSE_JOIN_MILLIS) }
        }
    }

    companion object {
        private const val RECEIVE_BUFFER_BYTES = 8 * 1_024
        private const val CLOSE_JOIN_MILLIS = 1_000L

        /** Public, loopback, multicast and IPv6 sources never receive a response. */
        internal fun isAllowedClientAddress(address: InetAddress?): Boolean =
            address is Inet4Address && address.isSiteLocalAddress
    }
}

/** One global bucket bounds reflection traffic without retaining any state per client. */
internal class LanStunResponseRateLimiter(
    private val clockNanos: () -> Long = System::nanoTime,
    private val responsesPerSecond: Int = 64,
    private val burstResponses: Int = 96,
) {
    private var availableTokens = burstResponses.toDouble()
    private var lastRefillNanos = clockNanos()

    init {
        require(responsesPerSecond > 0)
        require(burstResponses > 0)
    }

    fun tryAcquire(): Boolean {
        val now = clockNanos()
        val elapsedNanos = (now - lastRefillNanos).coerceAtLeast(0L)
        lastRefillNanos = now
        availableTokens = min(
            burstResponses.toDouble(),
            availableTokens + (elapsedNanos.toDouble() * responsesPerSecond / NANOS_PER_SECOND),
        )
        if (availableTokens < 1.0) return false
        availableTokens -= 1.0
        return true
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
