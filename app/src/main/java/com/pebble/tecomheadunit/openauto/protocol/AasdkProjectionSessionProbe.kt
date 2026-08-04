/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import com.pebble.tecomheadunit.openauto.credential.HeadUnitCredential
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal enum class AasdkProjectionSessionState {
    DISABLED,
    CONNECTION_FAILED,
    PROTOCOL_ERROR,
    VERSION_MISMATCH,
    TLS_CREDENTIAL_UNAVAILABLE,
    TLS_POLICY_REJECTED,
    TLS_HANDSHAKE_FAILED,
    AUTH_COMPLETE_SENT,
    SERVICE_DISCOVERY_RECEIVED,
}

internal data class AasdkProjectionSessionResult(
    val state: AasdkProjectionSessionState,
    val peerMajorVersion: Int? = null,
    val peerMinorVersion: Int? = null,
)

/**
 * Performs the pinned OpenAuto bootstrap on one transport:
 * VERSION -> TLS 1.2 client handshake -> AuthComplete(OK).
 *
 * This is intentionally a bounded diagnostic session. It reads only the first
 * encrypted ServiceDiscovery request, then closes; the response and media
 * channels belong to the long-lived projection session that follows it.
 */
internal class AasdkProjectionSessionProbe(
    private val openTransport: suspend (host: String, port: Int) -> AasdkByteTransport =
        { host, port ->
            AasdkTcpTransport(host = host, port = port).also { it.connect() }
        },
) {
    suspend fun probe(
        host: String,
        port: Int = AasdkTcpTransport.DEFAULT_PORT,
        credential: HeadUnitCredential?,
        buildBoundary: AasdkTlsBuildBoundary,
        peerTrustPolicy: AasdkTlsPeerTrustPolicy,
    ): AasdkProjectionSessionResult {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty()) {
            return result(AasdkProjectionSessionState.DISABLED)
        }
        if (port !in 1..65535) {
            return result(AasdkProjectionSessionState.CONNECTION_FAILED)
        }
        if (credential == null) {
            return result(AasdkProjectionSessionState.TLS_CREDENTIAL_UNAVAILABLE)
        }

        var transport: AasdkByteTransport? = null
        var tls: AasdkTlsEngineSession? = null
        var stage = Stage.CONNECT
        var peerMajor: Int? = null
        var peerMinor: Int? = null
        return try {
            transport = openTransport(normalizedHost, port)
            stage = Stage.VERSION
            val version = AasdkVersionNegotiator().negotiate(transport)
            peerMajor = version.major
            peerMinor = version.minor
            if (!version.matches) {
                return result(
                    AasdkProjectionSessionState.VERSION_MISMATCH,
                    peerMajor,
                    peerMinor,
                )
            }

            stage = Stage.TLS
            tls = AasdkTlsEngineSession(
                AasdkTlsEngineFactory.createClient(
                    credential = credential,
                    buildBoundary = buildBoundary,
                    peerTrustPolicy = peerTrustPolicy,
                ),
            )
            var step = tls.beginHandshake()
            sendHandshakeFlight(transport, step.outboundRecords)

            var exchanges = 0
            while (step.state == AasdkTlsState.HANDSHAKING) {
                if (++exchanges > MAX_HANDSHAKE_EXCHANGES) {
                    throw AasdkTlsEngineException("TLS handshake exchange limit exceeded")
                }
                if (!step.needsPeerData && step.outboundRecords.isEmpty()) {
                    throw AasdkTlsEngineException("TLS handshake made no transport progress")
                }
                val incoming = AasdkPlainMessageStream.readMessage(transport)
                val records = AasdkBootstrapCodec.parseTlsHandshake(incoming)
                step = tls.receiveHandshakeRecords(records)
                sendHandshakeFlight(transport, step.outboundRecords)
            }
            if (step.state != AasdkTlsState.ACTIVE) {
                throw AasdkTlsEngineException("TLS peer closed during handshake")
            }

            // The final TLS flight above must be flushed before this plain
            // indication. There is no AuthComplete ACK in the pinned AASDK.
            transport.write(AasdkBootstrapCodec.authCompleteSuccess())
            stage = Stage.DISCOVERY
            receiveServiceDiscovery(transport, tls)
            result(
                AasdkProjectionSessionState.SERVICE_DISCOVERY_RECEIVED,
                peerMajor,
                peerMinor,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: AasdkTlsPolicyException) {
            result(AasdkProjectionSessionState.TLS_POLICY_REJECTED, peerMajor, peerMinor)
        } catch (error: AasdkTlsEngineException) {
            result(AasdkProjectionSessionState.TLS_HANDSHAKE_FAILED, peerMajor, peerMinor)
        } catch (error: EOFException) {
            failureFor(stage, peerMajor, peerMinor)
        } catch (error: AasdkProtocolException) {
            failureFor(stage, peerMajor, peerMinor)
        } catch (error: IOException) {
            failureFor(stage, peerMajor, peerMinor)
        } catch (error: SecurityException) {
            failureFor(stage, peerMajor, peerMinor)
        } catch (error: Exception) {
            failureFor(stage, peerMajor, peerMinor)
        } finally {
            runCatching { tls?.close() }
            runCatching { transport?.close() }
        }
    }

    private suspend fun sendHandshakeFlight(
        transport: AasdkByteTransport,
        records: ByteArray,
    ) {
        if (records.isEmpty()) return
        AasdkBootstrapCodec.tlsHandshake(records).forEach { transport.write(it) }
    }

    private suspend fun receiveServiceDiscovery(
        transport: AasdkByteTransport,
        tls: AasdkTlsEngineSession,
    ) {
        val layer = AasdkTlsMessageLayer(tls)
        val assembler = AasdkPlainMessageAssembler()
        repeat(MAX_DISCOVERY_FRAMES) {
            val encrypted = AasdkFrameCodec.decodeFrame(AasdkFrameStream.readFrame(transport))
            val message = assembler.accept(layer.unwrapEncryptedFrame(encrypted)) ?: return@repeat
            if (
                message.channelId != AasdkFrameCodec.CONTROL_CHANNEL ||
                message.controlMessage ||
                message.payload.size < 2 ||
                readUInt16(message.payload, 0) != SERVICE_DISCOVERY_REQUEST
            ) {
                throw AasdkProtocolException("unexpected post-authentication message")
            }
            return
        }
        throw AasdkProtocolException("service discovery frame limit exceeded")
    }

    private fun failureFor(
        stage: Stage,
        peerMajor: Int?,
        peerMinor: Int?,
    ): AasdkProjectionSessionResult = result(
        when (stage) {
            Stage.CONNECT -> AasdkProjectionSessionState.CONNECTION_FAILED
            Stage.VERSION -> AasdkProjectionSessionState.PROTOCOL_ERROR
            Stage.TLS -> AasdkProjectionSessionState.TLS_HANDSHAKE_FAILED
            Stage.DISCOVERY -> AasdkProjectionSessionState.AUTH_COMPLETE_SENT
        },
        peerMajor,
        peerMinor,
    )

    private fun result(
        state: AasdkProjectionSessionState,
        peerMajor: Int? = null,
        peerMinor: Int? = null,
    ) = AasdkProjectionSessionResult(
        state = state,
        peerMajorVersion = peerMajor,
        peerMinorVersion = peerMinor,
    )

    private enum class Stage {
        CONNECT,
        VERSION,
        TLS,
        DISCOVERY,
    }

    private companion object {
        const val MAX_HANDSHAKE_EXCHANGES = 32
        const val MAX_DISCOVERY_FRAMES = 32
        const val SERVICE_DISCOVERY_REQUEST = 0x0005

        fun readUInt16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}
