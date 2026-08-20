package com.eigenkodex.navonweb.browser

import com.eigenkodex.navonweb.openauto.OpenAutoKeyAction
import com.eigenkodex.navonweb.openauto.OpenAutoKeyCode
import com.eigenkodex.navonweb.openauto.OpenAutoKeyEvent
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.AndroidAutoConnectionStatus
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserKeyInputEndpointTest {
    @Test
    fun decodesEveryAdvertisedButtonAsAtomicClick() {
        OpenAutoKeyCode.SUPPORTED
            .filterNot { it == OpenAutoKeyCode.SCROLL_WHEEL }
            .forEach { scanCode ->
                assertAccepted(
                    "{\"scanCode\":$scanCode,\"action\":\"click\"}",
                    OpenAutoKeyEvent(scanCode, OpenAutoKeyAction.CLICK),
                )
            }
    }

    @Test
    fun decodesScrollWheelAsOneDirectionalRelativeEvent() {
        assertAccepted(
            "{\"scanCode\":65536,\"action\":\"scroll\",\"delta\":-1}",
            OpenAutoKeyEvent(OpenAutoKeyCode.SCROLL_WHEEL, OpenAutoKeyAction.SCROLL_LEFT),
        )
        assertAccepted(
            "{\"delta\":1,\"action\":\"scroll\",\"scanCode\":65536}",
            OpenAutoKeyEvent(OpenAutoKeyCode.SCROLL_WHEEL, OpenAutoKeyAction.SCROLL_RIGHT),
        )
    }

    @Test
    fun rejectsUnsupportedCodesActionsAndAmbiguousShapes() {
        listOf(
            "{\"scanCode\":0,\"action\":\"click\"}",
            "{\"scanCode\":7,\"action\":\"click\"}",
            "{\"scanCode\":4,\"action\":\"CLICK\"}",
            "{\"scanCode\":4,\"action\":\"press\"}",
            "{\"scanCode\":4,\"action\":\"release\"}",
            "{\"scanCode\":4,\"action\":\"click\",\"delta\":1}",
            "{\"scanCode\":65536,\"action\":\"click\"}",
            "{\"scanCode\":65536,\"action\":\"scroll\"}",
            "{\"scanCode\":65536,\"action\":\"scroll\",\"delta\":0}",
            "{\"scanCode\":65536,\"action\":\"scroll\",\"delta\":2}",
            "{\"scanCode\":4,\"action\":\"click\",\"extra\":0}",
            "{\"scanCode\":4,\"scanCode\":4,\"action\":\"click\"}",
            "{\"scanCode\":4.0,\"action\":\"click\"}",
            "{\"scanCode\":04,\"action\":\"click\"}",
            "{\"scanCode\":4,\"action\":true}",
            "[]",
            "{}",
            "",
        ).forEach { body ->
            assertEquals(
                "unexpectedly accepted: $body",
                BrowserKeyInputDecodeResult.Invalid,
                decode(body),
            )
        }
    }

    @Test
    fun enforcesJsonMediaTypeQueryAndEndpointBodyLimit() {
        val body = "{\"scanCode\":4,\"action\":\"click\"}".toByteArray(StandardCharsets.UTF_8)
        assertTrue(isBrowserKeyJsonContentType("application/json"))
        assertTrue(isBrowserKeyJsonContentType("Application/JSON; Charset=UTF-8"))
        assertFalse(isBrowserKeyJsonContentType(null))
        assertFalse(isBrowserKeyJsonContentType("text/plain"))
        assertFalse(isBrowserKeyJsonContentType("application/json; charset=iso-8859-1"))
        assertFalse(isBrowserKeyJsonContentType("application/json; charset=utf-8; version=1"))

        assertEquals(
            BrowserKeyInputDecodeResult.UnsupportedMediaType,
            decodeBrowserKeyInput("text/plain", false, body),
        )
        assertEquals(
            BrowserKeyInputDecodeResult.Invalid,
            decodeBrowserKeyInput("application/json", true, body),
        )
        assertEquals(
            BrowserKeyInputDecodeResult.PayloadTooLarge,
            decodeBrowserKeyInput(
                "application/json",
                false,
                ByteArray(MAX_BROWSER_KEY_INPUT_BODY_BYTES + 1),
            ),
        )
        assertEquals(
            BrowserKeyInputDecodeResult.Invalid,
            decodeBrowserKeyInput(
                "application/json",
                false,
                byteArrayOf(0x7b, 0x22, 0x80.toByte()),
            ),
        )
    }

    @Test
    fun keyReadinessMatchesTouchConnectionBoundary() {
        assertTrue(
            BrowserProbeServer.isKeyInputAllowed(
                AndroidAutoConnectionStatus(state = AndroidAutoConnectionState.CONNECTED),
                keyInputReady = true,
            ),
        )
        assertFalse(
            BrowserProbeServer.isKeyInputAllowed(
                AndroidAutoConnectionStatus(state = AndroidAutoConnectionState.CONNECTED),
                keyInputReady = false,
            ),
        )
        assertFalse(BrowserProbeServer.isKeyInputAllowed(AndroidAutoConnectionStatus(), true))
    }

    @Test
    fun keyRouteExistsOnlyInAuthenticatedWebRtcControlDispatch() {
        val server = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/BrowserProbeServer.kt",
        )
        val controller = readProjectFile(
            "app/src/main/java/com/eigenkodex/navonweb/browser/webrtc/WebRtcProjectionController.kt",
        )
        val webRtcStart = server.indexOf("fun dispatchWebRtcControl(")
        val webRtcEnd = server.indexOf("private fun decodeRelayResponse", webRtcStart)
        val webRtcDispatch = server.substring(webRtcStart, webRtcEnd)
        val localStart = server.indexOf("private fun handleClient(client: Socket)")
        val localEnd = server.indexOf("private fun serveIndex", localStart)
        val localDispatch = server.substring(localStart, localEnd)
        val keyStart = server.indexOf("private fun serveKey(")
        val keyEnd = server.indexOf("private fun authenticatedDevice", keyStart)
        val keyHandler = server.substring(keyStart, keyEnd)

        assertTrue(webRtcDispatch.contains("parsed.path == \"/api/key\""))
        assertFalse(localDispatch.contains("request.path == \"/api/key\""))
        assertTrue(controller.contains("\"/api/key\","))
        assertTrue(keyHandler.contains("authenticatedDevice(request)"))
        assertTrue(keyHandler.contains("BrowserDevicePermission.READ_ONLY"))
        assertTrue(keyHandler.contains("isKeyInputAllowed(connection, inputReady)"))
        assertTrue(keyHandler.contains("runCatching { onKey(event) }.getOrDefault(false)"))
        assertTrue(keyHandler.contains("hasQueryParameters = request.hasQuery"))
        assertTrue(server.contains("hasQuery = uri.rawQuery != null"))
    }

    private fun assertAccepted(body: String, expected: OpenAutoKeyEvent) {
        assertEquals(BrowserKeyInputDecodeResult.Accepted(expected), decode(body))
    }

    private fun decode(body: String): BrowserKeyInputDecodeResult = decodeBrowserKeyInput(
        contentType = "application/json; charset=utf-8",
        hasQueryParameters = false,
        body = body.toByteArray(StandardCharsets.UTF_8),
    )

    private fun readProjectFile(path: String): String {
        val direct = File(path)
        if (direct.isFile) return direct.readText()
        val fromModule = File("..", path)
        check(fromModule.isFile) { "missing project file: $path" }
        return fromModule.readText()
    }
}
