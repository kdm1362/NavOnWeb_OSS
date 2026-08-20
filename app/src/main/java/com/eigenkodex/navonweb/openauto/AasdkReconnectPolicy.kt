/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import com.eigenkodex.navonweb.openauto.protocol.AasdkProtocolException
import com.eigenkodex.navonweb.openauto.protocol.AasdkTlsEngineException
import com.eigenkodex.navonweb.openauto.protocol.AasdkTlsPolicyException
import com.eigenkodex.navonweb.openauto.protocol.AasdkVersionMismatchException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Decides whether a failed projection session may be negotiated again.
 *
 * A retry always means opening a new TCP connection and performing VERSION,
 * TLS and service discovery again. Resuming a partially consumed AASDK/TLS
 * stream is deliberately forbidden because its framing and cipher state can no
 * longer be proven to match the peer.
 */
internal class AasdkReconnectPolicy(
    private val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    private val maximumDelayMillis: Long = DEFAULT_MAXIMUM_DELAY_MILLIS,
) {
    init {
        require(initialDelayMillis > 0L) { "initialDelayMillis must be positive" }
        require(maximumDelayMillis >= initialDelayMillis) {
            "maximumDelayMillis must be at least initialDelayMillis"
        }
    }

    fun decision(error: Throwable, consecutiveFailure: Int): AasdkReconnectDecision {
        require(consecutiveFailure > 0) { "consecutiveFailure must be positive" }
        val retryable = error is IOException
        return AasdkReconnectDecision(
            retry = retryable,
            delayMillis = if (retryable) delayFor(consecutiveFailure) else 0L,
            reason = safeReason(error),
        )
    }

    private fun delayFor(consecutiveFailure: Int): Long {
        var delay = initialDelayMillis
        repeat((consecutiveFailure - 1).coerceAtMost(MAX_DOUBLINGS)) {
            delay = (delay * 2L).coerceAtMost(maximumDelayMillis)
        }
        return delay
    }

    private fun safeReason(error: Throwable): String = when (error) {
        is AasdkLivenessTimeoutException -> "PING_TIMEOUT"
        is AasdkSurfaceUnavailableException -> "SURFACE_LOST"
        is AasdkRecoverableDecoderException -> "DECODER_RECOVERY"
        is AasdkDecoderUnavailableException -> "DECODER_UNAVAILABLE"
        is SocketTimeoutException -> "READ_TIMEOUT"
        is ConnectException -> "CONNECT_FAILED"
        is EOFException -> "PEER_DISCONNECTED"
        is SocketException -> "SOCKET_DISCONNECTED"
        is IOException -> "TRANSPORT_DISCONNECTED"
        is AasdkPeerShutdownException -> "PEER_SHUTDOWN"
        is AasdkVersionMismatchException -> "VERSION_MISMATCH"
        is AasdkTlsPolicyException -> "TLS_POLICY_REJECTED"
        is AasdkTlsEngineException -> "TLS_HANDSHAKE_FAILED"
        is AasdkProtocolException -> "PROTOCOL_ERROR"
        is SecurityException -> "SAFETY_OR_CREDENTIAL_REJECTED"
        else -> "SESSION_FAILED"
    }

    internal companion object {
        const val DEFAULT_INITIAL_DELAY_MILLIS = 1_000L
        const val DEFAULT_MAXIMUM_DELAY_MILLIS = 15_000L
        private const val MAX_DOUBLINGS = 62
    }
}

internal data class AasdkReconnectDecision(
    val retry: Boolean,
    val delayMillis: Long,
    val reason: String,
)
