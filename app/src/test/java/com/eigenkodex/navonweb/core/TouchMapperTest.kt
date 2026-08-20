package com.eigenkodex.navonweb.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TouchMapperTest {
    private val viewport = VideoViewport(800, 480)

    @Test
    fun mapsCornersWithoutOverflow() {
        val topLeft = TouchMapper.map(NormalizedTouch(TouchPhase.DOWN, 0.0, 0.0), viewport)
        val bottomRight = TouchMapper.map(NormalizedTouch(TouchPhase.UP, 1.0, 1.0), viewport)

        assertEquals(0, topLeft.x)
        assertEquals(0, topLeft.y)
        assertEquals(799, bottomRight.x)
        assertEquals(479, bottomRight.y)
    }

    @Test
    fun clampsOutOfRangeCoordinates() {
        val mapped = TouchMapper.map(NormalizedTouch(TouchPhase.MOVE, -10.0, 5.0), viewport)

        assertEquals(0, mapped.x)
        assertEquals(479, mapped.y)
    }

    @Test
    fun rejectsNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            TouchMapper.map(NormalizedTouch(TouchPhase.MOVE, Double.NaN, 0.5), viewport)
        }
    }
}
