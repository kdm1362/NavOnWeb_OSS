/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionSurfaceLossPolicyTest {
    @Test
    fun `keeps runtime when controller-owned decoder surface remains valid`() {
        assertEquals(
            ProjectionSurfaceDetachAction.KEEP_RUNTIME,
            ProjectionSurfaceLossPolicy.decide(
                runtimeUsesNativeWebRtcDecoder = true,
                nativeWebRtcDecoderSurfaceValid = true,
                fallbackDecoderSurfaceValid = false,
            ),
        )
    }

    @Test
    fun `background preview loss does not stop the native decoder used by JPEG and WebRTC`() {
        val action = ProjectionSurfaceLossPolicy.decide(
            runtimeUsesNativeWebRtcDecoder = true,
            nativeWebRtcDecoderSurfaceValid = true,
            fallbackDecoderSurfaceValid = true,
        )

        assertEquals(ProjectionSurfaceDetachAction.KEEP_RUNTIME, action)
    }

    @Test
    fun `keeps JPEG runtime when service fallback decoder remains valid`() {
        assertEquals(
            ProjectionSurfaceDetachAction.KEEP_RUNTIME,
            ProjectionSurfaceLossPolicy.decide(
                runtimeUsesNativeWebRtcDecoder = false,
                nativeWebRtcDecoderSurfaceValid = false,
                fallbackDecoderSurfaceValid = true,
            ),
        )
    }

    @Test
    fun `stops fallback runtime when its bound service decoder is invalid`() {
        assertEquals(
            ProjectionSurfaceDetachAction.STOP_RUNTIME,
            ProjectionSurfaceLossPolicy.decide(
                runtimeUsesNativeWebRtcDecoder = false,
                nativeWebRtcDecoderSurfaceValid = true,
                fallbackDecoderSurfaceValid = false,
            ),
        )
    }

    @Test
    fun `stops runtime when controller decoder surface is invalid`() {
        assertEquals(
            ProjectionSurfaceDetachAction.STOP_RUNTIME,
            ProjectionSurfaceLossPolicy.decide(
                runtimeUsesNativeWebRtcDecoder = true,
                nativeWebRtcDecoderSurfaceValid = false,
                fallbackDecoderSurfaceValid = true,
            ),
        )
    }
}
