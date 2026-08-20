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
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkReconnectPolicyTest {
    private val policy = AasdkReconnectPolicy()

    @Test
    fun `transport and liveness failures negotiate a new session`() {
        listOf(
            EOFException(),
            SocketTimeoutException(),
            ConnectException(),
            IOException(),
            AasdkLivenessTimeoutException(),
        ).forEach { error ->
            assertTrue(error.javaClass.simpleName, policy.decision(error, 1).retry)
        }
    }

    @Test
    fun `all transient decoder failures negotiate a fresh decoder session`() {
        AasdkRecoverableDecoderFailure.entries.forEach { failure ->
            val error: Throwable = AasdkRecoverableDecoderException(failure)
            val decision = policy.decision(error, 1)

            assertTrue(failure.name, error is IOException)
            assertTrue(failure.name, decision.retry)
            assertEquals(failure.name, "DECODER_RECOVERY", decision.reason)
            assertEquals(failure.name, 1_000L, decision.delayMillis)
        }
    }

    @Test
    fun `surface loss remains distinct and retryable`() {
        val error: Throwable = AasdkSurfaceUnavailableException()
        val decision = policy.decision(error, 1)

        assertTrue(error is IOException)
        assertTrue(decision.retry)
        assertEquals("SURFACE_LOST", decision.reason)
        assertEquals(1_000L, decision.delayMillis)
    }

    @Test
    fun `codec restart flags distinguish transient and fatal rejection`() {
        assertTrue(isRecoverableCodecFailure(isTransient = true, isRecoverable = false))
        assertTrue(isRecoverableCodecFailure(isTransient = false, isRecoverable = true))
        assertTrue(isRecoverableCodecFailure(isTransient = true, isRecoverable = true))
        assertFalse(isRecoverableCodecFailure(isTransient = false, isRecoverable = false))
    }

    @Test
    fun `peer shutdown and trust protocol or safety failures remain terminal`() {
        listOf(
            AasdkPeerShutdownException(),
            AasdkVersionMismatchException(2, 0),
            AasdkTlsPolicyException("rejected"),
            AasdkTlsEngineException("failed"),
            AasdkProtocolException("invalid frame"),
            AasdkDecoderUnavailableException(),
            SecurityException("not confirmed"),
            IllegalStateException("internal state"),
        ).forEach { error ->
            assertFalse(error.javaClass.simpleName, policy.decision(error, 1).retry)
        }
    }

    @Test
    fun `malformed video access unit remains a terminal protocol failure`() {
        val decision = policy.decision(
            AasdkProtocolException("video access unit exceeds decoder buffer"),
            1,
        )

        assertFalse(decision.retry)
        assertEquals("PROTOCOL_ERROR", decision.reason)
        assertEquals(0L, decision.delayMillis)
    }

    @Test
    fun `permanently unavailable decoder remains terminal`() {
        val decision = policy.decision(AasdkDecoderUnavailableException(), 1)

        assertFalse(decision.retry)
        assertEquals("DECODER_UNAVAILABLE", decision.reason)
        assertEquals(0L, decision.delayMillis)
    }

    @Test
    fun `retry delay grows exponentially and remains capped`() {
        assertEquals(1_000L, policy.decision(EOFException(), 1).delayMillis)
        assertEquals(2_000L, policy.decision(EOFException(), 2).delayMillis)
        assertEquals(4_000L, policy.decision(EOFException(), 3).delayMillis)
        assertEquals(8_000L, policy.decision(EOFException(), 4).delayMillis)
        assertEquals(15_000L, policy.decision(EOFException(), 5).delayMillis)
        assertEquals(15_000L, policy.decision(EOFException(), Int.MAX_VALUE).delayMillis)
    }

    @Test
    fun `safe reasons do not expose exception messages`() {
        assertEquals("PEER_DISCONNECTED", policy.decision(EOFException("secret"), 1).reason)
        assertEquals("READ_TIMEOUT", policy.decision(SocketTimeoutException("secret"), 1).reason)
        assertEquals("PING_TIMEOUT", policy.decision(AasdkLivenessTimeoutException(), 1).reason)
        assertEquals(
            "DECODER_RECOVERY",
            policy.decision(
                AasdkRecoverableDecoderException(
                    AasdkRecoverableDecoderFailure.CODEC_REJECTED,
                    IllegalStateException("secret"),
                ),
                1,
            ).reason,
        )
        assertEquals(
            "SURFACE_LOST",
            policy.decision(AasdkSurfaceUnavailableException(IllegalStateException("secret")), 1).reason,
        )
        assertEquals(
            "DECODER_UNAVAILABLE",
            policy.decision(AasdkDecoderUnavailableException(IllegalStateException("secret")), 1).reason,
        )
        assertEquals("PROTOCOL_ERROR", policy.decision(AasdkProtocolException("secret"), 1).reason)
    }
}
