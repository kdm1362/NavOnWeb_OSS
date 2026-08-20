/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.protocol

import java.io.EOFException
import java.net.ConnectException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkProjectionProbeTest {
    @Test
    fun `blank host leaves probe disabled without opening a transport`() = runBlocking {
        var opened = false
        val probe = AasdkProjectionProbe { _, _ ->
            opened = true
            FakeTransport(matchingResponse())
        }

        val result = probe.probe("   ")

        assertEquals(AasdkProjectionProbeState.DISABLED, result.state)
        assertFalse(opened)
        assertNull(result.peerMajorVersion)
        assertNull(result.peerMinorVersion)
    }

    @Test
    fun `matching version succeeds and always closes transport`() = runBlocking {
        val transport = FakeTransport(matchingResponse())
        val probe = AasdkProjectionProbe { host, port ->
            assertEquals("10.0.2.2", host)
            assertEquals(5277, port)
            transport
        }

        val result = probe.probe(" 10.0.2.2 ")

        assertEquals(AasdkProjectionProbeState.VERSION_ACCEPTED, result.state)
        assertEquals(1, result.peerMajorVersion)
        assertEquals(1, result.peerMinorVersion)
        assertEquals(1, transport.closeCount)
        assertTrue(transport.written.contentEquals(AasdkBootstrapCodec.versionRequest()))
    }

    @Test
    fun `reported mismatch is distinct and always closes transport`() = runBlocking {
        val transport = FakeTransport(mismatchResponse())
        val probe = AasdkProjectionProbe { _, _ -> transport }

        val result = probe.probe("10.0.2.2")

        assertEquals(AasdkProjectionProbeState.VERSION_MISMATCH, result.state)
        assertEquals(1, result.peerMajorVersion)
        assertEquals(0, result.peerMinorVersion)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `invalid protocol response is distinct and always closes transport`() = runBlocking {
        val transport = FakeTransport(byteArrayOf(0x00, 0x03, 0x00))
        val probe = AasdkProjectionProbe { _, _ -> transport }

        val result = probe.probe("10.0.2.2")

        assertEquals(AasdkProjectionProbeState.PROTOCOL_ERROR, result.state)
        assertEquals(1, transport.closeCount)
        assertFalse(result.message.contains("fixture exhausted"))
    }

    @Test
    fun `connection error is normalized without leaking exception details`() = runBlocking {
        val probe = AasdkProjectionProbe { _, _ ->
            throw ConnectException("secret-token at 192.0.2.44")
        }

        val result = probe.probe("private-host.example")

        assertEquals(AasdkProjectionProbeState.CONNECTION_FAILED, result.state)
        assertEquals("The TCP endpoint is unavailable.", result.message)
        assertFalse(result.message.contains("secret-token"))
        assertFalse(result.message.contains("192.0.2.44"))
        assertFalse(result.message.contains("private-host.example"))
    }

    @Test
    fun `transport IO error is normalized and still closes transport`() = runBlocking {
        val transport = FakeTransport(
            incoming = ByteArray(0),
            writeFailure = ConnectException("secret-token at 192.0.2.44"),
        )
        val probe = AasdkProjectionProbe { _, _ -> transport }

        val result = probe.probe("private-host.example")

        assertEquals(AasdkProjectionProbeState.CONNECTION_FAILED, result.state)
        assertEquals("The TCP endpoint is unavailable.", result.message)
        assertEquals(1, transport.closeCount)
        assertFalse(result.message.contains("secret-token"))
        assertFalse(result.message.contains("private-host.example"))
    }

    private class FakeTransport(
        private val incoming: ByteArray,
        private val writeFailure: Exception? = null,
    ) : AasdkByteTransport {
        var written = ByteArray(0)
        var closeCount = 0
        private var offset = 0

        override suspend fun write(data: ByteArray) {
            writeFailure?.let { throw it }
            written += data
        }

        override suspend fun readExactly(size: Int): ByteArray {
            if (incoming.size - offset < size) throw EOFException("fixture exhausted")
            return incoming.copyOfRange(offset, offset + size).also { offset += size }
        }

        override fun close() {
            closeCount += 1
        }
    }

    private companion object {
        fun matchingResponse() = versionResponse(minor = 1, status = 0x0000)

        fun mismatchResponse() = versionResponse(minor = 0, status = 0xFFFF)

        fun versionResponse(minor: Int, status: Int) =
            byteArrayOf(
                0x00,
                0x03,
                0x00,
                0x08,
                0x00,
                0x02,
                0x00,
                0x01,
                0x00,
                minor.toByte(),
                (status ushr 8).toByte(),
                status.toByte(),
            )
    }
}
