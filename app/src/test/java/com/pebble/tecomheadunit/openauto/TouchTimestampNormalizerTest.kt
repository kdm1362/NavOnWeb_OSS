/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.openauto.protocol.AasdkProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TouchTimestampNormalizerTest {
    @Test
    fun `nanoseconds remain strictly increasing nanoseconds`() {
        val timestamps = TouchTimestampNormalizer()

        assertEquals(2_000_999L, timestamps.normalize(2_000_999L))
        assertEquals(2_001_000L, timestamps.normalize(2_000_999L))
        assertEquals(2_001_001L, timestamps.normalize(1_000_000L))
        assertEquals(2_004_000L, timestamps.normalize(2_004_000L))
    }

    @Test
    fun `negative event timestamp is rejected`() {
        val timestamps = TouchTimestampNormalizer()

        assertThrows(AasdkProtocolException::class.java) {
            timestamps.normalize(-1L)
        }
    }
}
