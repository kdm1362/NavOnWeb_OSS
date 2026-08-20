/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserRelayGatewayTest {
    @Test
    fun requestAcceptsOnlyBoundedOriginRelativeBrowserApiCalls() {
        val request = BrowserRelayRequest(
            method = "POST",
            target = "/api/webrtc/session?codec=auto",
            headers = mapOf(
                "content-type" to "application/sdp",
                "x-browser-credential" to "a".repeat(43),
            ),
            body = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96".toByteArray(),
        )

        assertEquals("/api/webrtc/session?codec=auto", request.target)
    }

    @Test
    fun cloudPairingAcceptsTheExactLowercaseDeviceNameHeader() {
        val request = BrowserRelayRequest(
            method = "POST",
            target = "/api/pair",
            headers = mapOf(
                "x-pairing-code" to "12345678",
                "x-browser-device-name" to "Chrome · Windows",
            ),
        )

        assertEquals("Chrome · Windows", request.headers["x-browser-device-name"])
        assertEquals(
            setOf("x-pairing-code", "x-browser-device-name"),
            request.headers.keys,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun cloudPairingStillRejectsNonCanonicalHeaderCasing() {
        BrowserRelayRequest(
            method = "POST",
            target = "/api/pair",
            headers = mapOf("X-Browser-Device-Name" to "Chrome"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestRejectsAbsoluteTargets() {
        BrowserRelayRequest(method = "GET", target = "https://attacker.example/api/status")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestRejectsUntrustedHeaders() {
        BrowserRelayRequest(
            method = "GET",
            target = "/api/status",
            headers = mapOf("authorization" to "Bearer secret"),
        )
    }

    @Test
    fun responseDecoderPreservesStatusContentTypeAndBody() {
        val body = "{\"ok\":true}".toByteArray(StandardCharsets.UTF_8)
        val encoded = (
            "HTTP/1.1 202 Accepted\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII) + body

        val decoded = BrowserRelayResponse.decodeHttpResponse(encoded)!!

        assertEquals(202, decoded.status)
        assertEquals("application/json; charset=utf-8", decoded.contentType)
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun responseDecoderRejectsLengthMismatch() {
        val encoded = (
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                "Content-Length: 4\r\n\r\nok"
            ).toByteArray(StandardCharsets.US_ASCII)

        assertNull(BrowserRelayResponse.decodeHttpResponse(encoded))
    }
}
