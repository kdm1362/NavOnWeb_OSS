/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

internal enum class AasdkProjectionProbeState {
    DISABLED,
    CONNECTION_FAILED,
    PROTOCOL_ERROR,
    VERSION_MISMATCH,
    VERSION_ACCEPTED,
}

internal data class AasdkProjectionProbeResult(
    val state: AasdkProjectionProbeState,
    val message: String,
    val peerMajorVersion: Int? = null,
    val peerMinorVersion: Int? = null,
)

/**
 * Performs only the unencrypted AASDK VERSION exchange.
 *
 * TLS authentication, service discovery and media channels deliberately remain
 * outside this probe. The transport opener is injectable so the state machine
 * can be verified without a live Android Auto phone.
 */
internal class AasdkProjectionProbe(
    private val negotiator: AasdkVersionNegotiator = AasdkVersionNegotiator(),
    private val openTransport: suspend (host: String, port: Int) -> AasdkByteTransport =
        { host, port ->
            AasdkTcpTransport(host = host, port = port).also { it.connect() }
        },
) {
    suspend fun probe(
        host: String,
        port: Int = AasdkTcpTransport.DEFAULT_PORT,
    ): AasdkProjectionProbeResult {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty()) {
            return AasdkProjectionProbeResult(
                state = AasdkProjectionProbeState.DISABLED,
                message = "AASDK TCP probe is disabled.",
            )
        }
        if (port !in 1..65535) {
            return AasdkProjectionProbeResult(
                state = AasdkProjectionProbeState.CONNECTION_FAILED,
                message = "TCP port configuration is invalid.",
            )
        }

        var transport: AasdkByteTransport? = null
        return try {
            transport = openTransport(normalizedHost, port)
            val response = negotiator.negotiate(transport)
            if (response.matches) {
                AasdkProjectionProbeResult(
                    state = AasdkProjectionProbeState.VERSION_ACCEPTED,
                    message = "AASDK VERSION exchange was accepted.",
                    peerMajorVersion = response.major,
                    peerMinorVersion = response.minor,
                )
            } else {
                AasdkProjectionProbeResult(
                    state = AasdkProjectionProbeState.VERSION_MISMATCH,
                    message = "The peer reported an AASDK version mismatch.",
                    peerMajorVersion = response.major,
                    peerMinorVersion = response.minor,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AasdkProtocolException) {
            protocolFailure()
        } catch (error: EOFException) {
            protocolFailure()
        } catch (error: IOException) {
            connectionFailure(error)
        } catch (error: SecurityException) {
            connectionFailure(error)
        } catch (error: Exception) {
            if (transport == null) connectionFailure(error) else protocolFailure()
        } finally {
            runCatching { transport?.close() }
        }
    }

    private fun protocolFailure() =
        AasdkProjectionProbeResult(
            state = AasdkProjectionProbeState.PROTOCOL_ERROR,
            message = "The AASDK VERSION response was incomplete or invalid.",
        )

    private fun connectionFailure(error: Exception) =
        AasdkProjectionProbeResult(
            state = AasdkProjectionProbeState.CONNECTION_FAILED,
            message = normalizedConnectionMessage(error),
        )

    private fun normalizedConnectionMessage(error: Exception): String =
        when (error) {
            is SocketTimeoutException -> "The TCP connection timed out."
            is UnknownHostException -> "The TCP host could not be resolved."
            is ConnectException -> "The TCP endpoint is unavailable."
            is SecurityException -> "The TCP connection was blocked."
            else -> "The TCP connection failed."
        }
}
