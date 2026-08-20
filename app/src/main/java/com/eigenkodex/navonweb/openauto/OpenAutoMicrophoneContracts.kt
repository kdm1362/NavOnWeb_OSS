/*
 * Android adaptation of OpenAuto projection microphone interfaces.
 * Copyright (C) 2018 f1x.studio (Michal Szwaj)
 * Modifications Copyright (C) 2026 NavOnWeb contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import java.io.Closeable

/**
 * One owned, timestamped microphone packet ready for AASDK channel 7.
 *
 * [bytes] contains only signed little-endian PCM16 sample data. The AASDK message id and
 * eight-byte transport timestamp are deliberately added by the protocol layer.
 */
data class OpenAutoMicrophoneFrame(
    val capturedAtNanos: Long,
    val bytes: ByteArray,
) {
    init {
        require(capturedAtNanos >= 0L) { "microphone timestamp must be non-negative" }
        require(bytes.isNotEmpty()) { "microphone frame is empty" }
        require(bytes.size <= MAX_PCM_BYTES) { "microphone frame exceeds OpenAuto packet limit" }
        require(bytes.size % PCM16_BYTES_PER_SAMPLE == 0) {
            "microphone frame is not PCM16 sample-aligned"
        }
    }

    companion object {
        const val MAX_PCM_BYTES = 2_056
        private const val PCM16_BYTES_PER_SAMPLE = 2
    }
}

/**
 * Pull boundary used by the OpenAuto AV-input service.
 *
 * [stop] ends one AA microphone request while keeping the source reusable. [close] is reserved
 * for owning-service shutdown and permanently rejects later starts and input.
 */
interface OpenAutoMicrophoneSource : Closeable {
    val format: OpenAutoPcmFormat

    /** True only while a valid producer upload has been observed recently. */
    fun isAvailable(): Boolean

    /** Starts a fresh stream when a producer is currently available. */
    fun start(): Boolean

    /** Returns null on timeout, stop, or close. */
    fun poll(timeoutMillis: Long): OpenAutoMicrophoneFrame?

    /** Stops capture and discards queued audio without permanently closing the source. */
    fun stop()
}
