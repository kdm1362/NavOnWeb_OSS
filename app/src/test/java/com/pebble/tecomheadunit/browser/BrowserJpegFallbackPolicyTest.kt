/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import com.pebble.tecomheadunit.core.VideoViewport
import com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserJpegFallbackPolicyTest {
    @Test
    fun `fallback capture preserves every projection profile resolution`() {
        ProjectionVideoProfile.entries.forEach { profile ->
            val config = BrowserJpegFallbackPolicy.forProjectionProfile(profile)

            assertEquals(profile.width, config.width)
            assertEquals(profile.height, config.height)
        }
    }

    @Test
    fun `capture cadence decreases as pixel work increases`() {
        assertEquals(5, configFor(ProjectionVideoProfile.FREE_800X480).targetFramesPerSecond)
        assertEquals(4, configFor(ProjectionVideoProfile.PREMIUM_720P).targetFramesPerSecond)
        assertEquals(3, configFor(ProjectionVideoProfile.PREMIUM_1080P).targetFramesPerSecond)
    }

    @Test
    fun `higher resolution profiles retain UI edge quality within bounded memory`() {
        assertEquals(78, configFor(ProjectionVideoProfile.FREE_800X480).jpegQuality)
        assertEquals(82, configFor(ProjectionVideoProfile.PREMIUM_720P).jpegQuality)
        assertEquals(85, configFor(ProjectionVideoProfile.PREMIUM_1080P).jpegQuality)

        ProjectionVideoProfile.entries.forEach { profile ->
            assertTrue(
                BrowserJpegFallbackPolicy.forProjectionProfile(profile).bitmapByteCount <=
                    BrowserJpegFallbackPolicy.MAX_BITMAP_BYTES,
            )
        }
        assertEquals(2 * 1024 * 1024, BrowserJpegFallbackPolicy.FRAME_STORE_MAX_BYTES)
    }

    @Test
    fun `portrait fallback preserves pixels and profile quality without exceeding memory`() {
        val config = BrowserJpegFallbackPolicy.forProjectionViewport(
            profile = ProjectionVideoProfile.PREMIUM_1080P,
            viewport = VideoViewport(1080, 1920),
        )

        assertEquals(1080, config.width)
        assertEquals(1920, config.height)
        assertEquals(3, config.targetFramesPerSecond)
        assertEquals(85, config.jpegQuality)
        assertTrue(config.bitmapByteCount <= BrowserJpegFallbackPolicy.MAX_BITMAP_BYTES)
    }

    private fun configFor(profile: ProjectionVideoProfile): BrowserJpegFallbackConfig =
        BrowserJpegFallbackPolicy.forProjectionProfile(profile)
}
