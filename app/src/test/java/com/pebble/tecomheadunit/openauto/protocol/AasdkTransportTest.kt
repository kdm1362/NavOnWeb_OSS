/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import java.io.EOFException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkTransportTest {
    @Test
    fun `default TCP read timeout exceeds the thirty-five-second ping watchdog`() {
        assertTrue(AasdkTcpTransport.DEFAULT_READ_TIMEOUT_MILLIS >= 45_000)
    }

    @Test
    fun `version negotiator writes request and accepts matching response`() = runBlocking {
        val response = byteArrayOf(
            0x00,
            0x03,
            0x00,
            0x08,
            0x00,
            0x02,
            0x00,
            0x01,
            0x00,
            0x01,
            0x00,
            0x00,
        )
        val transport = FakeTransport(response, maximumReadChunk = 3)

        val result = AasdkVersionNegotiator().negotiate(transport)

        assertArrayEquals(AasdkBootstrapCodec.versionRequest(), transport.written)
        assertEquals(1, result.major)
        assertEquals(1, result.minor)
        assertTrue(result.matches)
    }

    @Test
    fun `truncated response fails closed`() {
        assertThrows(EOFException::class.java) {
            runBlocking {
                AasdkVersionNegotiator().negotiate(FakeTransport(byteArrayOf(0x00, 0x03, 0x00)))
            }
        }
    }

    private class FakeTransport(
        private val incoming: ByteArray,
        private val maximumReadChunk: Int = Int.MAX_VALUE,
    ) : AasdkByteTransport {
        var written = ByteArray(0)
        private var readOffset = 0

        override suspend fun write(data: ByteArray) {
            written += data
        }

        override suspend fun readExactly(size: Int): ByteArray {
            val output = ByteArray(size)
            var outputOffset = 0
            while (outputOffset < size) {
                val available = incoming.size - readOffset
                if (available <= 0) throw EOFException("fixture exhausted")
                val count = minOf(size - outputOffset, available, maximumReadChunk)
                incoming.copyInto(output, outputOffset, readOffset, readOffset + count)
                outputOffset += count
                readOffset += count
            }
            return output
        }

        override fun close() = Unit
    }
}
