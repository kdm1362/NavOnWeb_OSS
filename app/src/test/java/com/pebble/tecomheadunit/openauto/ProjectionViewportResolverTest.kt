/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.core.VideoViewport
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
    fun `Tesla style width contraction changes only the centred content viewport`() {
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
        val layout = ProjectionViewportResolver.resolve(base.viewport, 5, 3)

        val applied = layout.applyTo(base)

        assertEquals(base.viewport, applied.viewport)
        assertEquals(base.fps, applied.fps)
        assertEquals(base.dpi, applied.dpi)
        assertEquals(80, applied.totalMarginWidth)
        assertEquals(0, applied.totalMarginHeight)
        assertEquals(VideoViewport(1200, 720), applied.contentViewport)
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
    fun `landscape keeps baseline density while portrait preserves minimum logical width`() {
        val encoded = VideoViewport(800, 480)
        val landscape = ProjectionViewportResolver.resolve(
            encodedViewport = encoded,
            browserWidth = 1920,
            browserHeight = 1080,
            baseDensityDpi = 140,
            browserDevicePixelRatio = 1.0,
        )
        val portrait = ProjectionViewportResolver.resolve(
            encodedViewport = encoded,
            browserWidth = 576,
            browserHeight = 976,
            baseDensityDpi = 140,
            browserDevicePixelRatio = 3.0,
        )

        assertEquals(800, landscape.contentRect.width)
        assertEquals(448, landscape.contentRect.height)
        assertEquals(140, landscape.densityDpi)
        assertEquals(520, portrait.totalMarginWidth)
        assertEquals(280, portrait.contentRect.width)
        assertEquals(480, portrait.contentRect.height)
        assertEquals(92, portrait.densityDpi)
        assertTrue(
            portrait.contentRect.width * OpenAutoConfig.REFERENCE_DENSITY_DPI.toDouble() /
                portrait.densityDpi >=
                ProjectionViewportResolver.MIN_LOGICAL_CONTENT_WIDTH_DP.toDouble(),
        )
    }

    @Test
    fun `twenty by nine mobile portrait keeps 480 logical dp and narrower ratios fail closed`() {
        val encoded = VideoViewport(800, 480)

        val mobilePortrait = ProjectionViewportResolver.resolve(
            encodedViewport = encoded,
            browserWidth = 360,
            browserHeight = 800,
            baseDensityDpi = 140,
            browserDevicePixelRatio = 3.0,
        )

        assertEquals(584, mobilePortrait.totalMarginWidth)
        assertEquals(216, mobilePortrait.contentRect.width)
        assertEquals(480, mobilePortrait.contentRect.height)
        assertEquals(72, mobilePortrait.densityDpi)
        assertEquals(
            480.0,
            mobilePortrait.contentRect.width * OpenAutoConfig.REFERENCE_DENSITY_DPI.toDouble() /
                mobilePortrait.densityDpi,
            0.0,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(
                encodedViewport = encoded,
                browserWidth = 360,
                browserHeight = 840,
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
    fun `density uses its own two quantum change threshold`() {
        val encoded = VideoViewport(800, 480)
        val current = ProjectionViewportLayout(encoded, 520, 0, densityDpi = 92)
        val densityJitter = current.copy(densityDpi = 88)
        val densityChange = current.copy(densityDpi = 84)

        assertFalse(ProjectionViewportResolver.isMeaningfulChange(current, densityJitter))
        assertSame(current, ProjectionViewportResolver.stabilize(current, densityJitter))
        assertTrue(ProjectionViewportResolver.isMeaningfulChange(current, densityChange))
        assertSame(densityChange, ProjectionViewportResolver.stabilize(current, densityChange))
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
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionViewportResolver.resolve(
                encoded,
                browserWidth = 800,
                browserHeight = 480,
                baseDensityDpi = 144,
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
