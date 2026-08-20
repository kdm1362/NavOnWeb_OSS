/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.browser.BrowserRelayResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcControlDataChannelCodecTest {
    @Test
    fun `accepts CloudRelayTransport request and emits compatible response`() {
        val codec = WebRtcControlDataChannelCodec()
        val body = "viewport".toByteArray()
        val result = codec.decodeRequest(
            request(
                requestId = REQUEST_ID,
                method = "post",
                target = "/api/projection/viewport?width=1920&height=1080&devicePixelRatio=1",
                headers = "{\"content-type\":\"application/json\"," +
                    "\"X-Browser-Credential\":\"$BROWSER_CREDENTIAL\"," +
                    "\"x-viewport-client-id\":\"$VIEWPORT_CLIENT_ID\"}",
                body = body,
            ).toByteArray(),
        ) as WebRtcControlDecodeResult.Accepted

        assertEquals(REQUEST_ID, result.requestId)
        assertEquals("POST", result.request.method)
        assertEquals(
            "/api/projection/viewport?width=1920&height=1080&devicePixelRatio=1",
            result.request.target,
        )
        assertEquals("application/json", result.request.headers["content-type"])
        assertEquals(BROWSER_CREDENTIAL, result.request.headers["x-browser-credential"])
        assertEquals(VIEWPORT_CLIENT_ID, result.request.headers["x-viewport-client-id"])
        assertEquals(
            setOf("content-type", "x-browser-credential", "x-viewport-client-id"),
            result.request.headers.keys,
        )
        assertArrayEquals(body, result.request.body)
        assertEquals(1, codec.inFlightRequestCount)

        val encoded = codec.encodeResponse(
            REQUEST_ID,
            BrowserRelayResponse(200, "text/plain", "ok".toByteArray()),
        )

        assertEquals(
            "{\"type\":\"rpc_response\",\"requestId\":\"$REQUEST_ID\"," +
                "\"status\":200,\"contentType\":\"text/plain\",\"bodyBase64\":\"b2s=\"}",
            encoded,
        )
        assertEquals(0, codec.inFlightRequestCount)
        assertNull(
            codec.encodeResponse(
                REQUEST_ID,
                BrowserRelayResponse(200, "text/plain", ByteArray(0)),
            ),
        )
    }

    @Test
    fun `copies only remaining ByteBuffer bytes without advancing it`() {
        val codec = WebRtcControlDataChannelCodec()
        val encoded = request(REQUEST_ID).toByteArray()
        val buffer = ByteBuffer.allocate(encoded.size + 2).apply {
            put(7)
            put(encoded)
            put(9)
            flip()
            position(1)
            limit(1 + encoded.size)
        }
        val originalPosition = buffer.position()

        assertTrue(codec.decodeRequest(buffer, binary = false) is WebRtcControlDecodeResult.Accepted)
        assertEquals(originalPosition, buffer.position())
    }

    @Test
    fun `rejects binary oversized and malformed UTF-8 text as protocol violations`() {
        val codec = WebRtcControlDataChannelCodec()

        assertProtocolViolation(
            codec.decodeRequest(request(REQUEST_ID).toByteArray(), binary = true),
            WebRtcControlRejectReason.BINARY_MESSAGE,
        )
        assertProtocolViolation(
            codec.decodeRequest(ByteArray(WebRtcControlDataChannelCodec.MAX_MESSAGE_BYTES + 1)),
            WebRtcControlRejectReason.MESSAGE_TOO_LARGE,
        )
        assertProtocolViolation(
            codec.decodeRequest(byteArrayOf(0xC3.toByte(), 0x28)),
            WebRtcControlRejectReason.INVALID_UTF8,
        )
        assertEquals(0, codec.inFlightRequestCount)
    }

    @Test
    fun `rejects malformed duplicate-key and non-flat envelopes`() {
        val codec = WebRtcControlDataChannelCodec()
        val duplicateKey = request(REQUEST_ID).replace(
            "\"requestId\":\"$REQUEST_ID\"",
            "\"requestId\":\"$REQUEST_ID\",\"requestId\":\"$REQUEST_ID\"",
        )
        val extraField = request(REQUEST_ID).dropLast(1) + ",\"extra\":true}"
        val invalidId = request("too-short")

        assertProtocolViolation(
            codec.decodeRequest("not-json".toByteArray()),
            WebRtcControlRejectReason.MALFORMED_JSON,
        )
        assertProtocolViolation(
            codec.decodeRequest(duplicateKey.toByteArray()),
            WebRtcControlRejectReason.MALFORMED_JSON,
        )
        assertProtocolViolation(
            codec.decodeRequest(extraField.toByteArray()),
            WebRtcControlRejectReason.INVALID_ENVELOPE,
        )
        assertProtocolViolation(
            codec.decodeRequest(invalidId.toByteArray()),
            WebRtcControlRejectReason.INVALID_ENVELOPE,
        )
    }

    @Test
    fun `invalid request semantics and body bounds produce bounded 400 response`() {
        val codec = WebRtcControlDataChannelCodec()
        val oversizedBody = ByteArray(WebRtcControlDataChannelCodec.MAX_REQUEST_BODY_BYTES + 1)
        val invalidBase64 = request(REQUEST_ID).replace("\"bodyBase64\":\"\"", "\"bodyBase64\":\"%%%\"")

        listOf(
            request(REQUEST_ID, method = "PATCH"),
            request(REQUEST_ID, target = "https://attacker.invalid/"),
            invalidBase64,
            request(REQUEST_ID, body = oversizedBody),
        ).forEach { encoded ->
            val rejected = codec.decodeRequest(encoded.toByteArray()) as WebRtcControlDecodeResult.Rejected
            assertEquals(WebRtcControlRejectReason.INVALID_REQUEST, rejected.reason)
            assertFalse(rejected.shouldCloseChannel)
            assertErrorResponse(rejected.responseText, 400, "invalid_relay_request")
        }
        assertEquals(0, codec.inFlightRequestCount)
    }

    @Test
    fun `duplicate in-flight request id is a protocol violation`() {
        val codec = WebRtcControlDataChannelCodec()

        assertTrue(codec.decodeRequest(request(REQUEST_ID).toByteArray()) is WebRtcControlDecodeResult.Accepted)
        assertProtocolViolation(
            codec.decodeRequest(request(REQUEST_ID).toByteArray()),
            WebRtcControlRejectReason.DUPLICATE_REQUEST_ID,
        )
        assertEquals(1, codec.inFlightRequestCount)
        assertTrue(codec.abandonRequest(REQUEST_ID))
        assertFalse(codec.abandonRequest(REQUEST_ID))
    }

    @Test
    fun `limits session to sixteen in-flight requests and recovers after completion`() {
        val codec = WebRtcControlDataChannelCodec()
        repeat(WebRtcControlDataChannelCodec.MAX_IN_FLIGHT_REQUESTS) { index ->
            assertTrue(
                codec.decodeRequest(request(requestId(index)).toByteArray()) is
                    WebRtcControlDecodeResult.Accepted,
            )
        }

        val busy = codec.decodeRequest(request(requestId(99)).toByteArray()) as
            WebRtcControlDecodeResult.Rejected
        assertEquals(WebRtcControlRejectReason.TOO_MANY_IN_FLIGHT, busy.reason)
        assertFalse(busy.shouldCloseChannel)
        assertErrorResponse(busy.responseText, 429, "cloud_relay_busy")

        assertTrue(codec.abandonRequest(requestId(0)))
        assertTrue(
            codec.decodeRequest(request(requestId(100)).toByteArray()) is
                WebRtcControlDecodeResult.Accepted,
        )
        codec.clearInFlightRequests()
        assertEquals(0, codec.inFlightRequestCount)
    }

    @Test
    fun `token bucket allows burst ninety-six and refills sixty-four per second`() {
        var nowMillis = 1_000L
        val codec = WebRtcControlDataChannelCodec { nowMillis }
        repeat(WebRtcControlDataChannelCodec.REQUEST_BURST) { index ->
            val id = requestId(index)
            assertTrue(codec.decodeRequest(request(id).toByteArray()) is WebRtcControlDecodeResult.Accepted)
            assertTrue(codec.abandonRequest(id))
        }

        val limited = codec.decodeRequest(request(requestId(500)).toByteArray()) as
            WebRtcControlDecodeResult.Rejected
        assertEquals(WebRtcControlRejectReason.RATE_LIMITED, limited.reason)
        assertFalse(limited.shouldCloseChannel)
        assertErrorResponse(limited.responseText, 429, "cloud_relay_rate_limited")

        nowMillis += 500L
        repeat(32) { index ->
            val id = requestId(600 + index)
            assertTrue(codec.decodeRequest(request(id).toByteArray()) is WebRtcControlDecodeResult.Accepted)
            codec.abandonRequest(id)
        }
        val limitedAgain = codec.decodeRequest(request(requestId(700)).toByteArray()) as
            WebRtcControlDecodeResult.Rejected
        assertEquals(WebRtcControlRejectReason.RATE_LIMITED, limitedAgain.reason)
    }

    @Test
    fun `response body is bounded by DTO and final data-channel message limit`() {
        val codec = WebRtcControlDataChannelCodec()
        assertTrue(codec.decodeRequest(request(REQUEST_ID).toByteArray()) is WebRtcControlDecodeResult.Accepted)

        val encoded = checkNotNull(
            codec.encodeResponse(
                REQUEST_ID,
                BrowserRelayResponse(
                    200,
                    "application/octet-stream",
                    ByteArray(WebRtcControlDataChannelCodec.MAX_RESPONSE_BODY_BYTES),
                ),
            ),
        )

        assertTrue(encoded.toByteArray(StandardCharsets.UTF_8).size <= WebRtcControlDataChannelCodec.MAX_MESSAGE_BYTES)
        assertErrorResponse(encoded, 502, "relay_response_too_large")
    }

    @Test
    fun `outbound policy caps queued bytes plus the next fixed-size message`() {
        val messageBytes = WebRtcControlDataChannelCodec.MAX_MESSAGE_BYTES
        val exactRemaining = WebRtcControlDataChannelSendPolicy.MAX_BUFFERED_BYTES - messageBytes

        assertTrue(WebRtcControlDataChannelSendPolicy.canSend(exactRemaining, messageBytes))
        assertFalse(WebRtcControlDataChannelSendPolicy.canSend(exactRemaining + 1, messageBytes))
        assertFalse(
            WebRtcControlDataChannelSendPolicy.canSend(
                0,
                WebRtcControlDataChannelCodec.MAX_MESSAGE_BYTES + 1,
            ),
        )
        assertFalse(WebRtcControlDataChannelSendPolicy.canSend(-1, 1))
    }

    private fun request(
        requestId: String,
        method: String = "GET",
        target: String = "/api/status",
        headers: String = "{}",
        body: ByteArray = ByteArray(0),
    ): String = "{" +
        "\"type\":\"rpc_request\"," +
        "\"requestId\":\"$requestId\"," +
        "\"method\":\"$method\"," +
        "\"target\":\"$target\"," +
        "\"headers\":$headers," +
        "\"bodyBase64\":\"${Base64.getEncoder().encodeToString(body)}\"}"

    private fun requestId(index: Int): String = "request_${index.toString().padStart(12, '0')}"

    private fun assertProtocolViolation(
        result: WebRtcControlDecodeResult,
        reason: WebRtcControlRejectReason,
    ) {
        val rejected = result as WebRtcControlDecodeResult.Rejected
        assertEquals(reason, rejected.reason)
        assertTrue(rejected.shouldCloseChannel)
        assertNull(rejected.responseText)
    }

    private fun assertErrorResponse(encoded: String?, status: Int, error: String) {
        val text = checkNotNull(encoded)
        assertTrue(text.startsWith("{\"type\":\"rpc_response\",\"requestId\":"))
        assertTrue(text.contains("\"status\":$status"))
        assertTrue(text.contains("\"contentType\":\"application/json; charset=utf-8\""))
        val base64 = Regex("\\\"bodyBase64\\\":\\\"([^\\\"]*)\\\"").find(text)?.groupValues?.get(1)
        val body = String(Base64.getDecoder().decode(checkNotNull(base64)), StandardCharsets.UTF_8)
        assertEquals("{\"error\":\"$error\"}", body)
    }

    private companion object {
        const val REQUEST_ID = "abcdefghijklmnop"
        const val BROWSER_CREDENTIAL =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO12"
        const val VIEWPORT_CLIENT_ID = "0123456789abcdef"
    }
}
