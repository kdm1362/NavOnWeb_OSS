/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import com.eigenkodex.navonweb.core.VideoViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionViewportResolverTest {
    @Test
    fun `narrow browser produces a centred horizontal margin without resizing encoded frame`() {
        val encoded = VideoViewport(800, 480)

        val layout = ProjectionViewportResolver.resolve(
            encodedViewport = encoded,
            browserWidth = 4,
            browserHeight = 3,
        )

        assertEquals(encoded, layout.encodedViewport)
        assertEquals(160, layout.totalMarginWidth)
        assertEquals(0, layout.totalMarginHeight)
        assertEquals(140, layout.densityDpi)
        assertEquals(
            ProjectionContentRect(left = 80, top = 0, width = 640, height = 480),
            layout.contentRect,
        )
    }

    @Test
    fun `wider browser uses eight pixel quantization for vertical margins`() {
        val layout = ProjectionViewportResolver.resolve(
            encodedViewport = VideoViewport(800, 480),
            browserWidth = 16,
            browserHeight = 9,
        )

        // The exact 16:9 content height is 450 (30 total margin), rounded to 32.
        assertEquals(0, layout.totalMarginWidth)
        assertEquals(32, layout.totalMarginHeight)
        assertEquals(140, layout.densityDpi)
        assertEquals(
            ProjectionContentRect(left = 0, top = 16, width = 800, height = 448),
            layout.contentRect,
        )
    }

    @Test
    fun `Narrow dashboard width contraction changes only the centred content viewport`() {
        val parked = ProjectionViewportResolver.resolve(
            encodedViewport = VideoViewport(1280, 720),
            browserWidth = 1600,
            browserHeight = 900,
        )
        val driving = ProjectionViewportResolver.resolve(
            encodedViewport = VideoViewport(1280, 720),
            browserWidth = 1344,
            browserHeight = 900,
        )

        assertEquals(0, parked.totalMarginWidth)
        assertEquals(208, driving.totalMarginWidth)
        assertEquals(104, driving.contentRect.left)
        assertEquals(1072, driving.contentRect.width)
        assertEquals(720, driving.contentRect.height)
    }

    @Test
    fun `layout applies total margins while preserving base projection config`() {
        val base = ProjectionVideoProfile.PREMIUM_720P.toOpenAutoConfig()
        val layout = ProjectionViewportResolver.resolve(
            encodedViewport = base.viewport,
            browserWidth = 5,
            browserHeight = 3,
            baseDensityDpi = base.dpi,
        )

        val applied = layout.applyTo(base)

        assertEquals(base.viewport, applied.viewport)
        assertEquals(base.fps, applied.fps)
        assertEquals(base.dpi, applied.dpi)
        assertEquals(80, applied.totalMarginWidth)
        assertEquals(0, applied.totalMarginHeight)
        assertEquals(VideoViewport(1200, 720), applied.contentViewport)
    }

    @Test
    fun `each resolution keeps its profile density baseline at matching aspect ratio`() {
        val expectedDensities = mapOf(
            ProjectionVideoProfile.FREE_800X480 to 140,
            ProjectionVideoProfile.PREMIUM_720P to 200,
            ProjectionVideoProfile.PREMIUM_1080P to 220,
        )

        expectedDensities.forEach { (profile, expectedDensity) ->
            val layout = ProjectionViewportResolver.resolve(
                encodedViewport = profile.toOpenAutoConfig().viewport,
                browserWidth = profile.width,
                browserHeight = profile.height,
                baseDensityDpi = profile.dpi,
            )

            assertEquals(expectedDensity, layout.densityDpi)
        }
    }

    @Test
    fun `higher resolution profiles preserve their recommendation for narrow portrait content`() {
        val expectedDensities = mapOf(
            ProjectionVideoProfile.PREMIUM_720P to 200,
            ProjectionVideoProfile.PREMIUM_1080P to 220,
        )

        expectedDensities.forEach { (profile, expectedDensity) ->
            val layout = ProjectionViewportResolver.resolve(
                encodedViewport = profile.toOpenAutoConfig().viewport,
                browserWidth = 576,
                browserHeight = 976,
                baseDensityDpi = profile.dpi,
            )

            assertEquals(expectedDensity, layout.densityDpi)
            assertTrue(layout.densityDpi <= profile.dpi)
            assertTrue(
                layout.contentRect.width >= ProjectionViewportResolver.MIN_CONTENT_WIDTH_PIXELS,
            )
        }
    }

    @Test
    fun `hysteresis keeps one quantum jitter and accepts a two quantum change`() {
        val encoded = VideoViewport(800, 480)
        val current = ProjectionViewportLayout(encoded, totalMarginWidth = 160, totalMarginHeight = 0)
        val jitter = ProjectionViewportLayout(encoded, totalMarginWidth = 168, totalMarginHeight = 0)
        val changed = ProjectionViewportLayout(encoded, totalMarginWidth = 176, totalMarginHeight = 0)

        assertFalse(ProjectionViewportResolver.isMeaningfulChange(current, jitter))
        assertSame(current, ProjectionViewportResolver.stabilize(current, jitter))
        assertTrue(ProjectionViewportResolver.isMeaningfulChange(current, changed))
        assertSame(changed, ProjectionViewportResolver.stabilize(current, changed))
    }

    @Test
    fun `landscape and portrait encoded frames keep the same profile density`() {
        val landscapeEncoded = VideoViewport(800, 480)
        val landscape = ProjectionViewportResolver.resolve(
            encodedViewport = landscapeEncoded,
            browserWidth = 1920,
            browserHeight = 1080,
            baseDensityDpi = 140,
            browserDevicePixelRatio = 1.0,
        )
        val portrait = ProjectionViewportResolver.resolve(
            encodedViewport = VideoViewport(720, 1280),
            browserWidth = 576,
            browserHeight = 976,
            baseDensityDpi = 140,
            browserDevicePixelRatio = 3.0,
        )

        assertEquals(800, landscape.contentRect.width)
        assertEquals(448, landscape.contentRect.height)
        assertEquals(140, landscape.densityDpi)
        assertEquals(0, portrait.totalMarginWidth)
        assertEquals(64, portrait.totalMarginHeight)
        assertEquals(720, portrait.contentRect.width)
        assertEquals(1216, portrait.contentRect.height)
        assertEquals(140, portrait.densityDpi)
        assertTrue(
            portrait.contentRect.width * OpenAutoConfig.REFERENCE_DENSITY_DPI.toDouble() /
                portrait.densityDpi >=
                ProjectionViewportResolver.MIN_LOGICAL_CONTENT_WIDTH_DP.toDouble(),
        )
    }

    @Test
    fun `twenty by nine mobile portrait uses wide source content at fixed density`() {
        val encoded = VideoViewport(720, 1280)

        val mobilePortrait = ProjectionViewportResolver.resolve(
            encodedViewport = encoded,
            browserWidth = 360,
            browserHeight = 800,
            baseDensityDpi = 140,
            browserDevicePixelRatio = 3.0,
        )

        assertEquals(144, mobilePortrait.totalMarginWidth)
        assertEquals(576, mobilePortrait.contentRect.width)
        assertEquals(1280, mobilePortrait.contentRect.height)
        assertEquals(140, mobilePortrait.densityDpi)
        assertTrue(
            mobilePortrait.contentRect.width * OpenAutoConfig.REFERENCE_DENSITY_DPI.toDouble() /
                mobilePortrait.densityDpi >=
                ProjectionViewportResolver.MIN_LOGICAL_CONTENT_WIDTH_DP,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(
                encodedViewport = encoded,
                browserWidth = 1,
                browserHeight = 16_384,
            )
        }
    }

    @Test
    fun `device pixel ratio is validated but does not destabilize CSS logical layout`() {
        val encoded = VideoViewport(800, 480)
        val regular = ProjectionViewportResolver.resolve(encoded, 576, 976, 140, 1.0)
        val highDpi = ProjectionViewportResolver.resolve(encoded, 576, 976, 140, 4.0)

        assertEquals(regular, highDpi)
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(encoded, 576, 976, 140, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(encoded, 576, 976, 140, 9.0)
        }
    }

    @Test
    fun `trusted custom profile density is accepted and invalid density fails closed`() {
        val encoded = VideoViewport(800, 480)

        val layout = ProjectionViewportLayout(encoded, 520, 0, densityDpi = 92)
        val resolved = ProjectionViewportResolver.resolve(
            encodedViewport = encoded,
            browserWidth = 576,
            browserHeight = 976,
            baseDensityDpi = 92,
        )
        assertEquals(92, layout.densityDpi)
        assertEquals(92, resolved.densityDpi)

        assertEquals(
            93,
            ProjectionViewportResolver.resolve(
                encodedViewport = encoded,
                browserWidth = 576,
                browserHeight = 976,
                baseDensityDpi = 93,
            ).densityDpi,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(
                encodedViewport = encoded,
                browserWidth = 576,
                browserHeight = 976,
                baseDensityDpi = 68,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportLayout(encoded, 520, 0, densityDpi = 324)
        }
    }

    @Test
    fun `invalid browser geometry and unsafe aspect ratios fail closed`() {
        val encoded = VideoViewport(800, 480)

        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(encoded, browserWidth = 0, browserHeight = 480)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(
                encoded,
                browserWidth = ProjectionViewportResolver.MAX_VIEWPORT_EDGE_PIXELS + 1,
                browserHeight = 480,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(encoded, browserWidth = 1, browserHeight = 1_000)
        }
        assertEquals(
            145,
            ProjectionViewportResolver.resolve(
                encoded,
                browserWidth = 800,
                browserHeight = 480,
                baseDensityDpi = 145,
            ).densityDpi,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(
                encoded,
                browserWidth = 800,
                browserHeight = 480,
                baseDensityDpi = 68,
            )
        }
    }

    @Test
    fun `OpenAuto config rejects off-centre and empty margin geometry`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAutoConfig(
                viewport = VideoViewport(800, 480),
                totalMarginWidth = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAutoConfig(
                viewport = VideoViewport(800, 480),
                totalMarginWidth = 800,
            )
        }
    }
}
