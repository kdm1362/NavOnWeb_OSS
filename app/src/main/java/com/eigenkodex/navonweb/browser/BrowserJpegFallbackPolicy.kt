/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile

/** Bounded capture settings for the local JPEG transport used when WebRTC is unavailable. */
internal data class BrowserJpegFallbackConfig(
    val width: Int,
    val height: Int,
    val targetFramesPerSecond: Int,
    val jpegQuality: Int,
) {
    init {
        require(width > 0 && height > 0) { "invalid fallback dimensions" }
        require(targetFramesPerSecond in 1..10) { "invalid fallback frame rate" }
        require(jpegQuality in 1..100) { "invalid fallback JPEG quality" }
    }

    val bitmapByteCount: Long
        get() = width.toLong() * height * ARGB_8888_BYTES_PER_PIXEL

    private companion object {
        const val ARGB_8888_BYTES_PER_PIXEL = 4L
    }
}

/**
 * Keeps JPEG resolution identical to the negotiated Android Auto profile.
 *
 * JPEG compression is CPU-bound, so cadence decreases as pixel count grows. Quality rises with
 * resolution to avoid ringing around navigation labels and icons while the latest-only frame
 * store keeps encoded memory bounded.
 */
internal object BrowserJpegFallbackPolicy {
    const val FRAME_STORE_MAX_BYTES = 2 * 1024 * 1024
    const val MAX_BITMAP_BYTES = 1920L * 1080L * 4L

    fun forProjectionProfile(profile: ProjectionVideoProfile): BrowserJpegFallbackConfig =
        forProjectionViewport(profile, VideoViewport(profile.width, profile.height))

    /** Portrait and landscape variants use the same bounded pixel count and profile quality. */
    fun forProjectionViewport(
        profile: ProjectionVideoProfile,
        viewport: VideoViewport,
    ): BrowserJpegFallbackConfig {
        val (targetFramesPerSecond, jpegQuality) = when (profile) {
            ProjectionVideoProfile.FREE_800X480 -> 5 to 78
            ProjectionVideoProfile.PREMIUM_720P -> 4 to 82
            ProjectionVideoProfile.PREMIUM_1080P -> 3 to 85
        }
        return BrowserJpegFallbackConfig(
            width = viewport.width,
            height = viewport.height,
            targetFramesPerSecond = targetFramesPerSecond,
            jpegQuality = jpegQuality,
        ).also { config ->
            check(config.bitmapByteCount <= MAX_BITMAP_BYTES) {
                "fallback bitmap exceeds the memory budget"
            }
        }
    }
}
