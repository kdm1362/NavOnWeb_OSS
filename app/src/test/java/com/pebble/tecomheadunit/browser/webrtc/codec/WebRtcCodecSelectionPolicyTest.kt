/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcCodecSelectionPolicyTest {
    private val policy = WebRtcCodecSelectionPolicy()

    @Test
    fun `hardware H264 path is selected by auto`() {
        val deviceCapabilities = snapshot(
            capability(
                WebRtcVideoCodec.H264,
                EncoderAcceleration.HARDWARE,
                encoderName = "c2.qti.avc.encoder",
            ),
            capability(
                WebRtcVideoCodec.VP9,
                EncoderAcceleration.SOFTWARE,
                encoderName = "c2.android.vp9.encoder",
            ),
            capability(
                WebRtcVideoCodec.AV1,
                EncoderAcceleration.SOFTWARE,
                encoderName = "c2.android.av1.encoder",
            ),
        )

        val outcome = policy.select(
            preference = WebRtcCodecPreference.AUTO,
            local = deviceCapabilities,
            remote = RemoteVideoCodecCapabilities(WebRtcVideoCodec.entries.toSet()),
        )

        val result = (outcome as WebRtcCodecSelectionResult.Selected).value
        assertEquals(WebRtcVideoCodec.H264, result.codec)
        assertEquals("c2.qti.avc.encoder", result.encoder.encoderName)
    }

    @Test
    fun `auto prefers hardware H264 when modern hardware codecs also exist`() {
        val local = snapshot(
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.VP9, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.AV1, EncoderAcceleration.HARDWARE),
        )

        val result = selected(
            preference = WebRtcCodecPreference.AUTO,
            local = local,
            remoteCodecs = setOf(WebRtcVideoCodec.H264, WebRtcVideoCodec.VP9),
        )

        assertEquals(WebRtcVideoCodec.H264, result.codec)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `auto prefers hardware H264 over software AV1 VP9 and VP8`() {
        val local = snapshot(
            capability(WebRtcVideoCodec.AV1, EncoderAcceleration.SOFTWARE),
            capability(WebRtcVideoCodec.VP9, EncoderAcceleration.SOFTWARE),
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.VP8, EncoderAcceleration.SOFTWARE),
        )

        val result = selected(WebRtcCodecPreference.AUTO, local, WebRtcVideoCodec.entries.toSet())

        assertEquals(WebRtcVideoCodec.H264, result.codec)
        assertEquals(EncoderAcceleration.HARDWARE, result.encoder.acceleration)
    }

    @Test
    fun `explicit VP9 can select software encoder and reports warning`() {
        val local = snapshot(
            capability(WebRtcVideoCodec.VP9, EncoderAcceleration.SOFTWARE),
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
        )

        val result = selected(
            WebRtcCodecPreference.VP9,
            local,
            setOf(WebRtcVideoCodec.VP9, WebRtcVideoCodec.H264),
        )

        assertEquals(WebRtcVideoCodec.VP9, result.codec)
        assertTrue(CodecSelectionWarning.SOFTWARE_ENCODER_SELECTED in result.warnings)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `software explicit choice can be disabled by caller`() {
        val local = snapshot(
            capability(WebRtcVideoCodec.AV1, EncoderAcceleration.SOFTWARE),
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.VP8, EncoderAcceleration.HARDWARE),
        )

        val outcome = policy.select(
            preference = WebRtcCodecPreference.AV1,
            local = local,
            remote = RemoteVideoCodecCapabilities(WebRtcVideoCodec.entries.toSet()),
            allowSoftwareForExplicitChoice = false,
        )
        val result = (outcome as WebRtcCodecSelectionResult.Selected).value

        assertEquals(WebRtcVideoCodec.H264, result.codec)
        assertTrue(result.usedFallback)
        assertTrue(CodecSelectionWarning.REQUESTED_CODEC_UNAVAILABLE in result.warnings)
    }

    @Test
    fun `explicit codec absent from H264-only browser answer falls back to H264`() {
        val local = snapshot(
            capability(WebRtcVideoCodec.AV1, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
        )

        val outcome = policy.select(
            preference = WebRtcCodecPreference.AV1,
            local = local,
            remote = RemoteVideoCodecCapabilities(setOf(WebRtcVideoCodec.H264)),
        )

        val result = (outcome as WebRtcCodecSelectionResult.Selected).value
        assertEquals(WebRtcVideoCodec.H264, result.codec)
        assertTrue(result.usedFallback)
        assertTrue(CodecSelectionWarning.REQUESTED_CODEC_UNAVAILABLE in result.warnings)
    }

    @Test
    fun `encoder without surface input is never selected`() {
        val local = snapshot(
            capability(
                WebRtcVideoCodec.AV1,
                EncoderAcceleration.HARDWARE,
                supportsSurfaceInput = false,
            ),
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.VP8, EncoderAcceleration.HARDWARE),
        )

        val result = selected(WebRtcCodecPreference.AUTO, local, WebRtcVideoCodec.entries.toSet())

        assertEquals(WebRtcVideoCodec.H264, result.codec)
    }

    @Test
    fun `encoder that cannot sustain requested dimensions and rate is never selected`() {
        val local = snapshot(
            capability(
                WebRtcVideoCodec.VP9,
                EncoderAcceleration.HARDWARE,
                supportsRequestedSizeAndRate = false,
            ),
            capability(WebRtcVideoCodec.VP8, EncoderAcceleration.HARDWARE),
        )

        val result = selected(WebRtcCodecPreference.AUTO, local, WebRtcVideoCodec.entries.toSet())

        assertEquals(WebRtcVideoCodec.VP8, result.codec)
    }

    @Test
    fun `no common usable encoder returns unavailable`() {
        val local = snapshot(capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE))

        val outcome = policy.select(
            preference = WebRtcCodecPreference.AUTO,
            local = local,
            remote = RemoteVideoCodecCapabilities(setOf(WebRtcVideoCodec.AV1)),
        )

        assertTrue(outcome is WebRtcCodecSelectionResult.Unavailable)
    }

    @Test
    fun `unverified browser baseline permits hardware H264 before VP8`() {
        val local = snapshot(
            capability(WebRtcVideoCodec.AV1, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.VP8, EncoderAcceleration.HARDWARE),
        )

        val outcome = policy.select(
            preference = WebRtcCodecPreference.AUTO,
            local = local,
            remote = RemoteVideoCodecCapabilities.unverifiedBaseline(),
        )
        val result = (outcome as WebRtcCodecSelectionResult.Selected).value

        assertEquals(WebRtcVideoCodec.H264, result.codec)
    }

    @Test
    fun `explicit H264 request selects hardware H264`() {
        val local = snapshot(
            capability(WebRtcVideoCodec.H264, EncoderAcceleration.HARDWARE),
            capability(WebRtcVideoCodec.VP8, EncoderAcceleration.HARDWARE),
        )

        val result = selected(
            preference = WebRtcCodecPreference.H264,
            local = local,
            remoteCodecs = setOf(WebRtcVideoCodec.H264, WebRtcVideoCodec.VP8),
        )

        assertEquals(WebRtcVideoCodec.H264, result.codec)
        assertFalse(result.usedFallback)
        assertFalse(CodecSelectionWarning.REQUESTED_CODEC_UNAVAILABLE in result.warnings)
    }

    @Test
    fun `H264 is enabled and persisted storage restores H264`() {
        assertTrue(WebRtcOutboundCodecPolicy.H264_ENCODER_ENABLED)
        assertTrue(WebRtcOutboundCodecPolicy.isEncoderEnabled(WebRtcVideoCodec.H264))
        assertTrue(WebRtcVideoCodec.H264 in WebRtcOutboundCodecPolicy.enabledCodecs)
        assertEquals(WebRtcCodecPreference.H264, WebRtcCodecPreference.fromStorageValue("h264"))
    }

    @Test
    fun `each explicit preference and auto select the requested enabled codec`() {
        val local = snapshot(
            *WebRtcVideoCodec.entries.map { codec ->
                capability(codec, EncoderAcceleration.HARDWARE)
            }.toTypedArray(),
        )
        val remote = RemoteVideoCodecCapabilities(WebRtcVideoCodec.entries.toSet())

        assertEquals(
            WebRtcVideoCodec.H264,
            selected(WebRtcCodecPreference.AUTO, local, remote.supportedCodecs).codec,
        )
        assertEquals(
            WebRtcVideoCodec.H264,
            selected(WebRtcCodecPreference.H264, local, remote.supportedCodecs).codec,
        )
        assertEquals(
            WebRtcVideoCodec.VP8,
            selected(WebRtcCodecPreference.VP8, local, remote.supportedCodecs).codec,
        )
        assertEquals(
            WebRtcVideoCodec.VP9,
            selected(WebRtcCodecPreference.VP9, local, remote.supportedCodecs).codec,
        )
        assertEquals(
            WebRtcVideoCodec.AV1,
            selected(WebRtcCodecPreference.AV1, local, remote.supportedCodecs).codec,
        )
    }

    private fun selected(
        preference: WebRtcCodecPreference,
        local: MediaCodecCapabilitySnapshot,
        remoteCodecs: Set<WebRtcVideoCodec>,
    ): SelectedWebRtcCodec {
        val outcome = policy.select(
            preference = preference,
            local = local,
            remote = RemoteVideoCodecCapabilities(remoteCodecs),
        )
        return (outcome as WebRtcCodecSelectionResult.Selected).value
    }

    private fun snapshot(vararg capabilities: MediaCodecEncoderCapability) =
        MediaCodecCapabilitySnapshot(
            width = 800,
            height = 480,
            framesPerSecond = 60,
            encoders = capabilities.toList(),
        )

    private fun capability(
        codec: WebRtcVideoCodec,
        acceleration: EncoderAcceleration,
        encoderName: String = "test.${codec.storageValue}.${acceleration.name.lowercase()}",
        supportsSurfaceInput: Boolean = true,
        supportsRequestedSizeAndRate: Boolean = true,
    ) = MediaCodecEncoderCapability(
        codec = codec,
        encoderName = encoderName,
        acceleration = acceleration,
        supportsSurfaceInput = supportsSurfaceInput,
        supportsRequestedSizeAndRate = supportsRequestedSizeAndRate,
    )
}
