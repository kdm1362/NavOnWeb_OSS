/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.service

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
            ),
        )
    }

    @Test
    fun `stops runtime when WebRTC core is unavailable`() {
        assertEquals(
            ProjectionSurfaceDetachAction.STOP_RUNTIME,
            ProjectionSurfaceLossPolicy.decide(
                runtimeUsesNativeWebRtcDecoder = false,
                nativeWebRtcDecoderSurfaceValid = false,
            ),
        )
    }

    @Test
    fun `stops UI-owned runtime regardless of an unrelated native surface`() {
        assertEquals(
            ProjectionSurfaceDetachAction.STOP_RUNTIME,
            ProjectionSurfaceLossPolicy.decide(
                runtimeUsesNativeWebRtcDecoder = false,
                nativeWebRtcDecoderSurfaceValid = true,
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
            ),
        )
    }
}
