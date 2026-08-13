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

/** AASDK button event kind. Scroll-wheel input is relative, not a pressed key. */
enum class OpenAutoKeyAction {
    /** One atomic client action; the AASDK sender emits adjacent press and release indications. */
    CLICK,
    SCROLL_LEFT,
    SCROLL_RIGHT,
}

/** One allowlisted Android Auto scan-code event received from a controlling client. */
data class OpenAutoKeyEvent(
    val scanCode: Int,
    val action: OpenAutoKeyAction,
)

/** Explicit AASDK ButtonCode allowlist advertised to Android Auto. */
object OpenAutoKeyCode {
    const val MICROPHONE_2 = 0x01
    const val MENU = 0x02
    const val HOME = 0x03
    const val BACK = 0x04
    const val PHONE = 0x05
    const val CALL_END = 0x06
    const val UP = 0x13
    const val DOWN = 0x14
    const val LEFT = 0x15
    const val RIGHT = 0x16
    const val ENTER = 0x17
    const val MICROPHONE_1 = 0x54
    const val TOGGLE_PLAY = 0x55
    const val NEXT = 0x57
    const val PREV = 0x58
    const val PLAY = 0x7E
    const val PAUSE = 0x7F
    const val SCROLL_WHEEL = 65_536

    /** NONE (0) is deliberately excluded. */
    val SUPPORTED: Set<Int> = setOf(
        MICROPHONE_2,
        MENU,
        HOME,
        BACK,
        PHONE,
        CALL_END,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        ENTER,
        MICROPHONE_1,
        TOGGLE_PLAY,
        NEXT,
        PREV,
        PLAY,
        PAUSE,
        SCROLL_WHEEL,
    )

    fun supports(event: OpenAutoKeyEvent): Boolean = when (event.action) {
        OpenAutoKeyAction.CLICK ->
            event.scanCode in SUPPORTED && event.scanCode != SCROLL_WHEEL

        OpenAutoKeyAction.SCROLL_LEFT,
        OpenAutoKeyAction.SCROLL_RIGHT,
        -> event.scanCode == SCROLL_WHEEL
    }
}

interface OpenAutoInputSink {
    fun sendTouch(event: OpenAutoTouchEvent): Boolean

    /** Returns false when input is not bound, unsupported, or its ordered queue is full. */
    fun sendKey(event: OpenAutoKeyEvent): Boolean = false
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
