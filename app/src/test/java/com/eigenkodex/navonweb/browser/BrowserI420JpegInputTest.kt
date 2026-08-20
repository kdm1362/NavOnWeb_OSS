/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

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
    fun `copies chroma planes whose strides exceed the half-resolution row width`() {
        // Chroma strides (4 and 5) are wider than the two-byte chroma rows, so every padding
        // byte (99) must be skipped by the per-row bulk copies.
        val y = bufferAt(
            0,
            1, 2, 3, 4, 99,
            11, 12, 13, 14, 99,
            21, 22, 23, 24, 99,
            31, 32, 33, 34, 99,
        )
        val u = bufferAt(0, 41, 42, 99, 99, 43, 44)
        val v = bufferAt(0, 51, 52, 99, 99, 99, 53, 54)

        val nv21 = copyI420ToNv21(
            width = 4,
            height = 4,
            dataY = y,
            strideY = 5,
            dataU = u,
            strideU = 4,
            dataV = v,
            strideV = 5,
        )

        assertArrayEquals(
            byteArrayOf(
                1, 2, 3, 4,
                11, 12, 13, 14,
                21, 22, 23, 24,
                31, 32, 33, 34,
                51, 41, 52, 42,
                53, 43, 54, 44,
            ),
            nv21,
        )
        assertArrayEquals(intArrayOf(0, 0, 0), intArrayOf(y.position(), u.position(), v.position()))
    }

    @Test
    fun `copies planes from distinct non-zero buffer positions without mutating them`() {
        // Each plane starts at a different position (2, 3 and 1); the bytes before it and the
        // per-row padding (70, 60 and 40) must never reach the output.
        val y = bufferAt(2, 90, 90, 1, 2, 70, 3, 4, 70, 5, 6, 70, 7, 8)
        val u = bufferAt(3, 80, 80, 80, 21, 60, 22)
        val v = bufferAt(1, 50, 31, 40, 40, 32)

        val nv21 = copyI420ToNv21(
            width = 2,
            height = 4,
            dataY = y,
            strideY = 3,
            dataU = u,
            strideU = 2,
            dataV = v,
            strideV = 3,
        )

        assertArrayEquals(
            byteArrayOf(
                1, 2,
                3, 4,
                5, 6,
                7, 8,
                31, 21,
                32, 22,
            ),
            nv21,
        )
        assertArrayEquals(intArrayOf(2, 3, 1), intArrayOf(y.position(), u.position(), v.position()))
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

    private fun positionedBuffer(vararg values: Int): ByteBuffer = bufferAt(1, *values)

    /** Wraps [values] as bytes and moves the buffer to [startPosition] before returning it. */
    private fun bufferAt(startPosition: Int, vararg values: Int): ByteBuffer =
        ByteBuffer.wrap(ByteArray(values.size) { index -> values[index].toByte() })
            .apply { position(startPosition) }
}
