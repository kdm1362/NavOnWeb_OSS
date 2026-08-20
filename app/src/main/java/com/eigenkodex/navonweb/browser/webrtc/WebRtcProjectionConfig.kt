/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import com.eigenkodex.navonweb.core.VideoViewport

/** Network and encoder limits for the browser projection sender. */
data class WebRtcProjectionConfig(
    val width: Int = 800,
    val height: Int = 480,
    val framesPerSecond: Int = 30,
    val minBitrateBps: Int = 500_000,
    val startBitrateBps: Int = 2_500_000,
    val maxBitrateBps: Int = 4_000_000,
    val iceServers: List<WebRtcIceServerConfig> = emptyList(),
    /** Allows an explicitly selected AV1/VP9 software encoder, never an AUTO selection. */
    val allowSoftwareForExplicitCodec: Boolean = true,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(framesPerSecond in 1..120) { "framesPerSecond must be between 1 and 120" }
        require(minBitrateBps > 0) { "minBitrateBps must be positive" }
        require(startBitrateBps in minBitrateBps..maxBitrateBps) {
            "startBitrateBps must be between minBitrateBps and maxBitrateBps"
        }
        require(maxBitrateBps <= MAX_ALLOWED_BITRATE_BPS) {
            "maxBitrateBps exceeds the projection safety limit"
        }
    }

    companion object {
        fun forProjectionProfile(profile: ProjectionVideoProfile): WebRtcProjectionConfig =
            forProjectionViewport(
                profile = profile,
                viewport = VideoViewport(profile.width, profile.height),
            )

        /**
         * Keeps the selected profile's transport limits while sizing the immutable WebRTC source
         * to the exact Android Auto frame negotiated for the current browser orientation.
         */
        fun forProjectionViewport(
            profile: ProjectionVideoProfile,
            viewport: VideoViewport,
        ): WebRtcProjectionConfig =
            WebRtcProjectionConfig(
                width = viewport.width,
                height = viewport.height,
                framesPerSecond = profile.webRtcFramesPerSecond,
                minBitrateBps = profile.webRtcMinBitrateBps,
                startBitrateBps = profile.webRtcStartBitrateBps,
                maxBitrateBps = profile.webRtcMaxBitrateBps,
            )

        private const val MAX_ALLOWED_BITRATE_BPS = 20_000_000
    }
}

/** ICE server credentials are kept in the authenticated signaling response, never in a URL query. */
data class WebRtcIceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {
    init {
        require(urls.isNotEmpty()) { "ICE server needs at least one URL" }
        require(urls.all(::isValidIceUrl)) { "invalid ICE server URL" }
        require(username?.length.orZero() <= MAX_CREDENTIAL_CHARS) { "ICE username is too long" }
        require(credential?.length.orZero() <= MAX_CREDENTIAL_CHARS) { "ICE credential is too long" }
    }

    private companion object {
        const val MAX_CREDENTIAL_CHARS = 1_024

        fun isValidIceUrl(value: String): Boolean {
            if (value.length !in 6..2_048 || value.any(Char::isWhitespace)) return false
            return value.startsWith("stun:", ignoreCase = true) ||
                value.startsWith("stuns:", ignoreCase = true) ||
                value.startsWith("turn:", ignoreCase = true) ||
                value.startsWith("turns:", ignoreCase = true)
        }

        fun Int?.orZero(): Int = this ?: 0
    }
}
