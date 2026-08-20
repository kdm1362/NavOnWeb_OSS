/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import com.eigenkodex.navonweb.openauto.protocol.AasdkProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoTimestampNormalizerTest {
    @Test
    fun `keeps microsecond timestamps relative and monotonic`() {
        val normalizer = VideoTimestampNormalizer(fallbackFrameDurationUs = 16_667)

        assertEquals(0L, normalizer.normalize(2_000_000).presentationTimeUs)
        val second = normalizer.normalize(2_016_667)
        assertEquals(16_667L, second.presentationTimeUs)
        assertEquals(VideoTimestampUnit.MICROSECONDS, second.unit)
    }

    @Test
    fun `converts nanosecond deltas to microseconds`() {
        val normalizer = VideoTimestampNormalizer(fallbackFrameDurationUs = 16_667)

        normalizer.normalize(4_000_000_000)
        val second = normalizer.normalize(4_016_666_667)

        assertEquals(16_666L, second.presentationTimeUs)
        assertEquals(VideoTimestampUnit.NANOSECONDS, second.unit)
    }

    @Test
    fun `untimestamped media advances by configured frame duration`() {
        val normalizer = VideoTimestampNormalizer(fallbackFrameDurationUs = 16_667)

        assertEquals(0L, normalizer.normalize(null).presentationTimeUs)
        assertEquals(16_667L, normalizer.normalize(null).presentationTimeUs)
    }

    @Test
    fun `untimestamped media between raw frames keeps later PTS monotonic`() {
        val normalizer = VideoTimestampNormalizer(fallbackFrameDurationUs = 16_667)

        assertEquals(0L, normalizer.normalize(2_000_000).presentationTimeUs)
        assertEquals(16_667L, normalizer.normalize(null).presentationTimeUs)
        val nextRaw = normalizer.normalize(2_016_667)

        assertEquals(33_334L, nextRaw.presentationTimeUs)
        assertEquals(VideoTimestampUnit.MICROSECONDS, nextRaw.unit)
    }

    @Test
    fun `long startup gap does not lock microsecond stream as nanoseconds`() {
        val normalizer = VideoTimestampNormalizer(fallbackFrameDurationUs = 16_667)

        normalizer.normalize(1_000_000)
        val afterGap = normalizer.normalize(2_500_000)
        val continuous = normalizer.normalize(2_516_667)

        assertEquals(VideoTimestampUnit.UNKNOWN, afterGap.unit)
        assertEquals(16_667L, afterGap.presentationTimeUs)
        assertEquals(VideoTimestampUnit.MICROSECONDS, continuous.unit)
        assertEquals(33_334L, continuous.presentationTimeUs)
    }

    @Test
    fun `rejects repeated or reversed raw timestamps`() {
        val normalizer = VideoTimestampNormalizer(fallbackFrameDurationUs = 16_667)
        normalizer.normalize(100)

        assertThrows(AasdkProtocolException::class.java) { normalizer.normalize(100) }
        normalizer.reset()
        normalizer.normalize(200)
        assertThrows(AasdkProtocolException::class.java) { normalizer.normalize(199) }
    }
}
