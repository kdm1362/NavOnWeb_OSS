package com.eigenkodex.navonweb.browser.webrtc

import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcProjectionConfigTest {
    @Test
    fun `portrait source keeps selected profile rate limits`() {
        val profile = ProjectionVideoProfile.PREMIUM_1080P

        val config = WebRtcProjectionConfig.forProjectionViewport(
            profile = profile,
            viewport = VideoViewport(1080, 1920),
        )

        assertEquals(1080, config.width)
        assertEquals(1920, config.height)
        assertEquals(profile.webRtcFramesPerSecond, config.framesPerSecond)
        assertEquals(profile.webRtcMinBitrateBps, config.minBitrateBps)
        assertEquals(profile.webRtcStartBitrateBps, config.startBitrateBps)
        assertEquals(profile.webRtcMaxBitrateBps, config.maxBitrateBps)
    }
}
