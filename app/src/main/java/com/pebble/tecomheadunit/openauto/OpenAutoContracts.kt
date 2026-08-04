/*
 * Android adaptation of OpenAuto projection interfaces.
 * Copyright (C) 2018 f1x.studio (Michal Szwaj)
 * Modifications Copyright (C) 2026 Tecom Head Unit contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto

import com.pebble.tecomheadunit.core.OpenAutoTouchEvent
import com.pebble.tecomheadunit.core.VideoViewport

data class OpenAutoConfig(
    val endpointHost: String = "127.0.0.1",
    val endpointPort: Int = 5277,
    val viewport: VideoViewport = VideoViewport(800, 480),
    val fps: Int = 60,
    val dpi: Int = 160,
    /** Total unused width advertised around a horizontally centred AA content viewport. */
    val totalMarginWidth: Int = 0,
    /** Total unused height advertised around a vertically centred AA content viewport. */
    val totalMarginHeight: Int = 0,
) {
    init {
        require(totalMarginWidth >= 0 && totalMarginWidth < viewport.width) {
            "total margin width must leave a non-empty content viewport"
        }
        require(totalMarginHeight >= 0 && totalMarginHeight < viewport.height) {
            "total margin height must leave a non-empty content viewport"
        }
        require(totalMarginWidth % 2 == 0 && totalMarginHeight % 2 == 0) {
            "centred viewport margins must be even"
        }
    }

    /** Encoded frame size remains fixed; only this usable content rectangle changes. */
    val contentViewport: VideoViewport
        get() = VideoViewport(
            width = viewport.width - totalMarginWidth,
            height = viewport.height - totalMarginHeight,
        )

    companion object {
        const val REFERENCE_DENSITY_DPI = 160
    }
}

data class EncodedVideoFrame(
    val timestampMicros: Long,
    val bytes: ByteArray,
    val offset: Int = 0,
    val length: Int = bytes.size,
    val codecConfigOnly: Boolean = false,
) {
    init {
        require(offset >= 0 && length > 0 && offset <= bytes.size - length) {
            "invalid encoded video frame range"
        }
        require(timestampMicros >= 0) { "invalid video timestamp" }
    }
}

interface OpenAutoVideoSink {
    fun open(config: OpenAutoConfig): Boolean
    fun onEncodedFrame(frame: EncodedVideoFrame)
    fun stop()
}

interface OpenAutoInputSink {
    fun sendTouch(event: OpenAutoTouchEvent): Boolean
}

enum class EngineState {
    STOPPED,
    STARTING,
    BENCH_READY,
    NATIVE_READY,
    RUNNING,
    ERROR,
}

data class EngineSnapshot(
    val state: EngineState,
    val detail: String,
    val nativeRuntimeLinked: Boolean,
)

interface OpenAutoEngine : OpenAutoInputSink {
    fun start(config: OpenAutoConfig): Result<Unit>
    fun stop()
    fun snapshot(): EngineSnapshot
}
