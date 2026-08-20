/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level request parsing that backs the keep-alive socket loop: framing must be exact so a
 * second request on the same connection starts at the right byte, bodies must survive as raw
 * bytes, and the persistence flag must follow HTTP/1.1 Connection semantics.
 */
class BrowserHttpRequestParserTest {
    @Test
    fun `two pipelined requests parse back to back from one stream`() {
        val bytes = (
            "POST /api/touch?phase=down HTTP/1.1\r\n" +
                "Content-Length: 4\r\n" +
                "\r\n" +
                "ab12" +
                "GET /api/status HTTP/1.1\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        val input = ByteArrayInputStream(bytes)

        val first = BrowserProbeServer.HttpRequest.read(input)
        assertNotNull(first)
        assertEquals("POST", first!!.method)
        assertEquals("/api/touch", first.path)
        assertEquals("down", first.query["phase"])
        assertArrayEquals("ab12".toByteArray(StandardCharsets.US_ASCII), first.body)

        val second = BrowserProbeServer.HttpRequest.read(input)
        assertNotNull(second)
        assertEquals("GET", second!!.method)
        assertEquals("/api/status", second.path)
        assertEquals(0, second.body.size)

        assertNull(BrowserProbeServer.HttpRequest.read(input))
    }

    @Test
    fun `binary body bytes are preserved exactly`() {
        val payload = ByteArray(256) { index -> index.toByte() }
        val header = (
            "POST /api/microphone HTTP/1.1\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        val request = BrowserProbeServer.HttpRequest.read(ByteArrayInputStream(header + payload))

        assertNotNull(request)
        assertArrayEquals(payload, request!!.body)
    }

    @Test
    fun `keep alive capability follows version and connection tokens`() {
        fun parsed(requestHead: String): BrowserProbeServer.HttpRequest? =
            BrowserProbeServer.HttpRequest.read(
                ByteArrayInputStream(requestHead.toByteArray(StandardCharsets.ISO_8859_1)),
            )

        assertTrue(parsed("GET / HTTP/1.1\r\n\r\n")!!.keepAliveCapable)
        assertFalse(parsed("GET / HTTP/1.0\r\n\r\n")!!.keepAliveCapable)
        assertFalse(parsed("GET / HTTP/1.1\r\nConnection: close\r\n\r\n")!!.keepAliveCapable)
        assertFalse(
            parsed("GET / HTTP/1.1\r\nConnection: keep-alive, Close\r\n\r\n")!!.keepAliveCapable,
        )
        assertTrue(parsed("GET / HTTP/1.1\r\nConnection: keep-alive\r\n\r\n")!!.keepAliveCapable)
    }

    @Test
    fun `duplicate content length is rejected as a smuggling vector`() {
        val request = BrowserProbeServer.HttpRequest.read(
            ByteArrayInputStream(
                (
                    "POST /api/pair HTTP/1.1\r\n" +
                        "Content-Length: 4\r\n" +
                        "Content-Length: 2\r\n" +
                        "\r\n" +
                        "abcd"
                    ).toByteArray(StandardCharsets.ISO_8859_1),
            ),
        )
        assertNull(request)
    }

    @Test
    fun `transfer encoding remains rejected`() {
        val request = BrowserProbeServer.HttpRequest.read(
            ByteArrayInputStream(
                (
                    "POST /api/pair HTTP/1.1\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n"
                    ).toByteArray(StandardCharsets.ISO_8859_1),
            ),
        )
        assertNull(request)
    }

    @Test
    fun `truncated body fails closed`() {
        val request = BrowserProbeServer.HttpRequest.read(
            ByteArrayInputStream(
                (
                    "POST /api/pair HTTP/1.1\r\n" +
                        "Content-Length: 8\r\n" +
                        "\r\n" +
                        "abc"
                    ).toByteArray(StandardCharsets.ISO_8859_1),
            ),
        )
        assertNull(request)
    }

    @Test
    fun `gzip negotiation honors q values`() {
        assertFalse(BrowserProbeServer.acceptEncodingAllowsGzip(null))
        assertFalse(BrowserProbeServer.acceptEncodingAllowsGzip("identity"))
        assertTrue(BrowserProbeServer.acceptEncodingAllowsGzip("gzip"))
        assertTrue(BrowserProbeServer.acceptEncodingAllowsGzip("br, gzip ; q=1.0"))
        assertTrue(BrowserProbeServer.acceptEncodingAllowsGzip("GZIP;q=0.5"))
        assertFalse(BrowserProbeServer.acceptEncodingAllowsGzip("gzip;q=0"))
        assertFalse(BrowserProbeServer.acceptEncodingAllowsGzip("gzip;q=0.0, br"))
    }
}
