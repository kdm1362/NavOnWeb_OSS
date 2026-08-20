/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkVideoPacketCodecTest {
    @Test
    fun `decodes big endian timestamp and strips the AV envelope`() {
        val packet = AasdkVideoPacketCodec.decode(
            bytes(
                0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0xE2, 0x40,
                0x00, 0x00, 0x00, 0x01, 0x65,
            ),
        )

        assertEquals(123_456L, packet.rawTimestamp)
        assertFalse(packet.codecConfigOnly)
        assertArrayEquals(
            bytes(0x00, 0x00, 0x00, 0x01, 0x65),
            packet.bytes.copyOfRange(packet.dataOffset, packet.dataOffset + packet.dataLength),
        )
    }

    @Test
    fun `decodes codec config without a timestamp`() {
        val packet = AasdkVideoPacketCodec.decode(
            bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x67),
        )

        assertEquals(null, packet.rawTimestamp)
        assertTrue(packet.codecConfigOnly)
        assertArrayEquals(
            bytes(0x00, 0x00, 0x00, 0x01, 0x67),
            packet.bytes.copyOfRange(packet.dataOffset, packet.dataOffset + packet.dataLength),
        )
    }

    @Test
    fun `untimestamped mixed SPS and IDR remains normal media`() {
        val packet = AasdkVideoPacketCodec.decode(
            bytes(
                0x00, 0x01,
                0x00, 0x00, 0x01, 0x67, 0x11,
                0x00, 0x00, 0x00, 0x01, 0x65, 0x22,
            ),
        )

        assertFalse(packet.codecConfigOnly)
    }

    @Test
    fun `rejects empty media and timestamps outside MediaCodec range`() {
        assertThrows(AasdkProtocolException::class.java) {
            AasdkVideoPacketCodec.decode(bytes(0x00, 0x01))
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkVideoPacketCodec.decode(
                bytes(
                    0x00, 0x00,
                    0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x01,
                ),
            )
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
