/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc.codec

import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcCodecModelsTest {
    @Test
    fun `persisted preference parsing is case insensitive and trimmed`() {
        assertEquals(WebRtcCodecPreference.VP9, WebRtcCodecPreference.fromStorageValue(" VP9 "))
    }

    @Test
    fun `unknown persisted preference safely becomes auto`() {
        assertEquals(WebRtcCodecPreference.AUTO, WebRtcCodecPreference.fromStorageValue("mpeg2"))
        assertEquals(WebRtcCodecPreference.AUTO, WebRtcCodecPreference.fromStorageValue(null))
    }

    @Test
    fun `platform codec flags take precedence over name heuristic`() {
        assertEquals(
            EncoderAcceleration.HARDWARE,
            EncoderAccelerationClassifier.classify(
                codecName = "c2.android.avc.encoder",
                platformHardwareAccelerated = true,
                platformSoftwareOnly = false,
            ),
        )
    }

    @Test
    fun `known Android software codec names are classified conservatively`() {
        assertEquals(
            EncoderAcceleration.SOFTWARE,
            EncoderAccelerationClassifier.classify(
                codecName = "c2.android.av1.encoder",
                platformHardwareAccelerated = null,
                platformSoftwareOnly = null,
            ),
        )
    }

    @Test
    fun `unidentified pre Android 10 encoder remains unknown rather than assumed hardware`() {
        assertEquals(
            EncoderAcceleration.UNKNOWN,
            EncoderAccelerationClassifier.classify(
                codecName = "OMX.vendor.video.encoder.avc",
                platformHardwareAccelerated = null,
                platformSoftwareOnly = null,
            ),
        )
    }
}
