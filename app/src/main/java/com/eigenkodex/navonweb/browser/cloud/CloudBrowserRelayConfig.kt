/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import com.eigenkodex.navonweb.BuildConfig
import java.net.URI

data class CloudBrowserRelayConfig(
    val browserPageOrigin: String,
    val signalingWebSocketOrigin: String,
) {
    init {
        require(validOrigin(browserPageOrigin, "https")) { "invalid cloud browser origin" }
        require(validOrigin(signalingWebSocketOrigin, "wss")) { "invalid cloud signaling origin" }
    }

    fun deviceWebSocketUrl(roomId: String): String {
        require(ROOM_ID_PATTERN.matches(roomId)) { "invalid relay room id" }
        return "$signalingWebSocketOrigin/ws/device/$roomId"
    }

    /** The browser route is recovered from its secure cookie after entering the pairing code. */
    fun browserUrl(): String = browserPageOrigin

    fun devicePairingRegistrationUrl(): String =
        URI(signalingWebSocketOrigin).let { signaling ->
            URI(
                "https",
                signaling.rawUserInfo,
                signaling.host,
                signaling.port,
                "/bootstrap/device",
                null,
                null,
            ).toASCIIString()
        }

    companion object {
        private val ROOM_ID_PATTERN = Regex("^[A-Za-z0-9_-]{22}$")

        fun configuredOrNull(): CloudBrowserRelayConfig? {
            val browser = BuildConfig.CLOUD_BROWSER_PAGE_URL.trim().removeSuffix("/")
            val signaling = BuildConfig.CLOUD_SIGNALING_WEBSOCKET_URL.trim().removeSuffix("/")
            if (browser.isEmpty() || signaling.isEmpty()) return null
            return runCatching { CloudBrowserRelayConfig(browser, signaling) }.getOrNull()
        }

        internal fun validOrigin(value: String, expectedScheme: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals(expectedScheme, ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        }.getOrDefault(false)
    }
}
