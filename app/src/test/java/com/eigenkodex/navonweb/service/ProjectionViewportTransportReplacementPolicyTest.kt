package com.eigenkodex.navonweb.service

import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.openauto.OpenAutoConfig
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import com.eigenkodex.navonweb.openauto.ProjectionViewportResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionViewportTransportReplacementPolicyTest {
    @Test
    fun `profile replacement preserves portrait orientation and content aspect`() {
        val currentLayout = ProjectionViewportResolver.resolve(
            encodedViewport = VideoViewport(1080, 1920),
            browserWidth = 360,
            browserHeight = 800,
            baseDensityDpi = 140,
        )
        val currentConfig = currentLayout.applyTo(
            OpenAutoConfig(viewport = currentLayout.encodedViewport, dpi = 140),
        )

        val replacement =
            ProjectionViewportTransportReplacementPolicy.replacementLayoutForProfile(
                profile = ProjectionVideoProfile.PREMIUM_720P,
                currentConfig = currentConfig,
            )

        assertEquals(VideoViewport(720, 1280), replacement.encodedViewport)
        assertEquals(144, replacement.totalMarginWidth)
        assertEquals(0, replacement.totalMarginHeight)
        assertEquals(200, replacement.densityDpi)
        assertEquals(576, replacement.contentRect.width)
        assertEquals(1280, replacement.contentRect.height)
    }

    @Test
    fun `profile replacement preserves landscape orientation`() {
        val current = ProjectionVideoProfile.PREMIUM_1080P.toOpenAutoConfig()

        val replacement =
            ProjectionViewportTransportReplacementPolicy.replacementLayoutForProfile(
                profile = ProjectionVideoProfile.PREMIUM_720P,
                currentConfig = current,
            )

        assertEquals(VideoViewport(1280, 720), replacement.encodedViewport)
        assertEquals(0, replacement.totalMarginWidth)
        assertEquals(0, replacement.totalMarginHeight)
    }

    @Test
    fun `profile replacement uses the saved profile DPI`() {
        val current = ProjectionVideoProfile.PREMIUM_1080P.toOpenAutoConfig()

        val replacement =
            ProjectionViewportTransportReplacementPolicy.replacementLayoutForProfile(
                profile = ProjectionVideoProfile.PREMIUM_720P,
                currentConfig = current,
                configuredDensityDpi = 180,
            )

        assertEquals(180, replacement.densityDpi)
    }

    @Test
    fun `session fallback prevents a profile change from retrying portrait enum`() {
        val currentLayout = ProjectionViewportResolver.resolve(
            encodedViewport = VideoViewport(1080, 1920),
            browserWidth = 800,
            browserHeight = 1280,
            baseDensityDpi = 140,
        )
        val currentConfig = currentLayout.applyTo(
            OpenAutoConfig(viewport = currentLayout.encodedViewport, dpi = 140),
        )

        val replacement =
            ProjectionViewportTransportReplacementPolicy.replacementLayoutForProfile(
                profile = ProjectionVideoProfile.PREMIUM_720P,
                currentConfig = currentConfig,
                portraitEncodedViewportsEnabled = false,
            )

        assertEquals(VideoViewport(1280, 720), replacement.encodedViewport)
        assertEquals(832, replacement.totalMarginWidth)
        assertEquals(200, replacement.densityDpi)
    }

    @Test
    fun `margin only viewport change preserves the current media pipeline`() {
        val encoded = VideoViewport(1080, 1920)

        assertFalse(
            ProjectionViewportTransportReplacementPolicy.requiresMediaPipelineReplacement(
                currentViewport = encoded,
                requestedViewport = encoded,
            ),
        )
        assertTrue(
            ProjectionViewportTransportReplacementPolicy.requiresMediaPipelineReplacement(
                currentViewport = VideoViewport(1920, 1080),
                requestedViewport = encoded,
            ),
        )
    }

    @Test
    fun `healthy native sender requires a prepared replacement`() {
        assertFalse(
            ProjectionViewportTransportReplacementPolicy.canCommit(
                currentWebRtcAvailable = true,
                replacementWebRtcAvailable = false,
            ),
        )
        assertTrue(
            ProjectionViewportTransportReplacementPolicy.canCommit(
                currentWebRtcAvailable = true,
                replacementWebRtcAvailable = true,
            ),
        )
    }

    @Test
    fun `jpeg only session can rotate without native sender`() {
        assertTrue(
            ProjectionViewportTransportReplacementPolicy.canCommit(
                currentWebRtcAvailable = false,
                replacementWebRtcAvailable = false,
            ),
        )
    }
}
