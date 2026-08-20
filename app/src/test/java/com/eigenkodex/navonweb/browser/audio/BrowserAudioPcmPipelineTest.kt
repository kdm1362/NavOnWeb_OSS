/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.audio

import com.eigenkodex.navonweb.audio.AudioStreamAccessTier
import com.eigenkodex.navonweb.audio.Pcm16Format
import com.eigenkodex.navonweb.openauto.OpenAutoAudioChannel
import com.eigenkodex.navonweb.openauto.OpenAutoPcmFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAudioPcmPipelineTest {
    @Test
    fun freeMediaIsServerEnforcedMonoSixteenKilohertz() {
        val pipeline = BrowserAudioPcmPipeline(AudioStreamAccessTier.FREE)
        val subscription = checkNotNull(pipeline.streamHub.subscribe(BrowserAudioTrack.MEDIA))
        assertEquals(Pcm16Format(16_000, 1), subscription.format)
        assertTrue(pipeline.open(BrowserAudioTrack.MEDIA, Pcm16Format(48_000, 2)))

        // Three 48 kHz stereo frames produce one 16 kHz mono frame: averages are 2k, 4k, 6k.
        val input = pcm(1_000, 3_000, 3_000, 5_000, 5_000, 7_000)
        assertTrue(pipeline.onPcm16LittleEndian(BrowserAudioTrack.MEDIA, input, 0, input.size))

        assertArrayEquals(pcm(2_000), subscription.poll(0))
    }

    @Test
    fun premiumMediaPreservesStereoSamplesExactly() {
        val pipeline = BrowserAudioPcmPipeline(AudioStreamAccessTier.PREMIUM)
        val subscription = checkNotNull(pipeline.streamHub.subscribe(BrowserAudioTrack.MEDIA))
        assertEquals(Pcm16Format(48_000, 2), subscription.format)
        assertTrue(pipeline.open(BrowserAudioTrack.MEDIA, Pcm16Format(48_000, 2)))
        val input = pcm(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(), -1234, 5678)

        assertTrue(pipeline.onPcm16LittleEndian(BrowserAudioTrack.MEDIA, input, 0, input.size))

        assertArrayEquals(input, subscription.poll(0))
    }

    @Test
    fun rejectsWrongDescriptorAndPartialFrames() {
        val pipeline = BrowserAudioPcmPipeline(AudioStreamAccessTier.FREE)
        assertFalse(pipeline.open(BrowserAudioTrack.SYSTEM, Pcm16Format(48_000, 2)))
        assertTrue(pipeline.open(BrowserAudioTrack.SYSTEM, Pcm16Format(16_000, 1)))
        assertFalse(
            pipeline.onPcm16LittleEndian(
                BrowserAudioTrack.SYSTEM,
                byteArrayOf(1),
                0,
                1,
            ),
        )
    }

    @Test
    fun openAutoSinkCallbacksReachTheEntitlementPipeline() {
        val pipeline = BrowserAudioPcmPipeline(AudioStreamAccessTier.FREE)
        val subscription = checkNotNull(pipeline.streamHub.subscribe(BrowserAudioTrack.MEDIA))
        val channel = OpenAutoAudioChannel.MEDIA
        val input = pcm(1_000, 3_000, 3_000, 5_000, 5_000, 7_000)

        assertTrue(pipeline.onOpen(channel, channel.format))
        assertTrue(pipeline.onStart(channel))
        assertTrue(
            pipeline.onPcmFrame(
                OpenAutoPcmFrame(channel, channel.format, rawTimestamp = 1L, bytes = input),
            ),
        )

        assertArrayEquals(pcm(2_000), subscription.poll(0))
    }

    @Test
    fun suspendedAudioCanStartAgainWithoutReopeningTheAasdkChannel() {
        val pipeline = BrowserAudioPcmPipeline(AudioStreamAccessTier.FREE)
        val channel = OpenAutoAudioChannel.SYSTEM
        val subscription = checkNotNull(pipeline.streamHub.subscribe(BrowserAudioTrack.SYSTEM))

        assertTrue(pipeline.onOpen(channel, channel.format))
        assertTrue(pipeline.onStart(channel))
        pipeline.onSuspend(channel)
        assertTrue(pipeline.onStart(channel))
        assertTrue(
            pipeline.onPcmFrame(
                OpenAutoPcmFrame(channel, channel.format, rawTimestamp = null, bytes = pcm(321)),
            ),
        )

        assertArrayEquals(pcm(321), subscription.poll(0))
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { output ->
        samples.forEachIndexed { index, sample ->
            val value = sample.toShort().toInt()
            output[index * 2] = value.toByte()
            output[index * 2 + 1] = (value shr 8).toByte()
        }
    }
}
