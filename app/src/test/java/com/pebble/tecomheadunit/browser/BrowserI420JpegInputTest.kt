/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserI420JpegInputTest {
    @Test
    fun `copies padded I420 planes from their current positions into NV21`() {
        val y = positionedBuffer(
            99,
            1, 2, 3, 4, 88, 88,
            5, 6, 7, 8,
        )
        val u = positionedBuffer(77, 9, 10, 66)
        val v = positionedBuffer(55, 11, 12, 44)

        val nv21 = copyI420ToNv21(
            width = 4,
            height = 2,
            dataY = y,
            strideY = 6,
            dataU = u,
            strideU = 3,
            dataV = v,
            strideV = 3,
        )

        assertArrayEquals(
            byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                11, 9, 12, 10,
            ),
            nv21,
        )
        // Absolute/duplicate reads must not mutate libwebrtc's plane positions.
        assertArrayEquals(intArrayOf(1, 1, 1), intArrayOf(y.position(), u.position(), v.position()))
    }

    @Test
    fun `rejects a truncated strided plane before copying`() {
        assertThrows(IllegalArgumentException::class.java) {
            copyI420ToNv21(
                width = 4,
                height = 4,
                dataY = ByteBuffer.allocate(15),
                strideY = 4,
                dataU = ByteBuffer.allocate(4),
                strideU = 2,
                dataV = ByteBuffer.allocate(4),
                strideV = 2,
            )
        }
    }

    @Test
    fun `rejects odd dimensions unsupported by NV21`() {
        assertThrows(IllegalArgumentException::class.java) {
            copyI420ToNv21(
                width = 3,
                height = 2,
                dataY = ByteBuffer.allocate(6),
                strideY = 3,
                dataU = ByteBuffer.allocate(2),
                strideU = 2,
                dataV = ByteBuffer.allocate(2),
                strideV = 2,
            )
        }
    }

    private fun positionedBuffer(vararg values: Int): ByteBuffer =
        ByteBuffer.wrap(ByteArray(values.size) { index -> values[index].toByte() })
            .apply { position(1) }
}
