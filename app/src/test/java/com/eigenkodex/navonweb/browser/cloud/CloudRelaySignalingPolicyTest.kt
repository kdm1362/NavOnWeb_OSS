/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import com.eigenkodex.navonweb.browser.BrowserRelayRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRelaySignalingPolicyTest {
    @Test
    fun allowsOnlyPairingMetadataGeometryAndWebRtcSignaling() {
        listOf(
            "GET" to "/health",
            "POST" to "/api/pair",
            "GET" to "/api/status",
            "GET" to "/api/projection/profile",
            "GET" to "/api/projection/viewport",
            "POST" to "/api/projection/viewport?width=1080&height=1920&devicePixelRatio=1",
            "GET" to "/api/webrtc/capabilities",
            "POST" to "/api/webrtc/session?codec=auto",
            "GET" to "/api/webrtc/session/abcdefghijklmnop",
            "DELETE" to "/api/webrtc/session/abcdefghijklmnop",
        ).forEach { (method, target) ->
            assertTrue("$method $target", allowed(method, target))
        }
    }

    @Test
    fun rejectsTouchMediaAndOtherApplicationTraffic() {
        listOf(
            "POST" to "/api/touch?phase=down&x=0.5&y=0.5",
            "GET" to "/api/notices",
            "GET" to "/api/frame.jpg",
            "GET" to "/api/audio/media",
            "GET" to "/api/audio/speech",
            "GET" to "/api/audio/system",
            "POST" to "/api/microphone",
            "POST" to "/api/projection/profile",
        ).forEach { (method, target) ->
            assertFalse("$method $target", allowed(method, target))
        }
    }

    private fun allowed(method: String, target: String): Boolean =
        isAllowedCloudRelaySignalingRequest(BrowserRelayRequest(method, target))
}
