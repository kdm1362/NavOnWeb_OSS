/*
 * Android adaptation of OpenAuto's InputDevice coordinate mapping.
 * Copyright (C) 2018 f1x.studio (Michal Szwaj)
 * Modifications Copyright (C) 2026 NavOnWeb contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.core

import kotlin.math.roundToInt

enum class TouchPhase {
    DOWN,
    MOVE,
    UP,
    CANCEL,
}

data class NormalizedTouch(
    val phase: TouchPhase,
    val x: Double,
    val y: Double,
    val pointerId: Int = 0,
    // Current Android Auto InputReport senders use a monotonic nanosecond
    // timestamp, matching Android's elapsed-realtime clock family.
    val timestampNanos: Long = System.nanoTime(),
)

data class OpenAutoTouchEvent(
    val phase: TouchPhase,
    val x: Int,
    val y: Int,
    val pointerId: Int,
    val timestampNanos: Long,
)

data class VideoViewport(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
    }
}

object TouchMapper {
    fun map(touch: NormalizedTouch, viewport: VideoViewport): OpenAutoTouchEvent {
        require(touch.x.isFinite() && touch.y.isFinite()) { "touch coordinates must be finite" }
        val clampedX = touch.x.coerceIn(0.0, 1.0)
        val clampedY = touch.y.coerceIn(0.0, 1.0)

        return OpenAutoTouchEvent(
            phase = touch.phase,
            x = (clampedX * (viewport.width - 1)).roundToInt(),
            y = (clampedY * (viewport.height - 1)).roundToInt(),
            pointerId = touch.pointerId.coerceAtLeast(0),
            timestampNanos = touch.timestampNanos,
        )
    }
}
