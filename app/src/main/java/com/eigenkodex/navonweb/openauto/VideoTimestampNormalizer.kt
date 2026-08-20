/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import com.eigenkodex.navonweb.openauto.protocol.AasdkProtocolException

internal enum class VideoTimestampUnit {
    UNKNOWN,
    MICROSECONDS,
    NANOSECONDS,
}

internal data class NormalizedVideoTimestamp(
    val presentationTimeUs: Long,
    val unit: VideoTimestampUnit,
)

/**
 * AASDK stores an opaque uint64 timestamp while MediaCodec requires microseconds.
 * The first continuous frame delta distinguishes the observed us/ns dialect.
 */
internal class VideoTimestampNormalizer(
    private val fallbackFrameDurationUs: Long,
) {
    private var lastRaw: Long? = null
    private var divisor: Long? = null
    private var lastPresentationUs = -1L

    init {
        require(fallbackFrameDurationUs > 0) { "invalid fallback frame duration" }
    }

    fun normalize(rawTimestamp: Long?): NormalizedVideoTimestamp {
        if (rawTimestamp == null) {
            val next = if (lastPresentationUs < 0) 0L else lastPresentationUs + fallbackFrameDurationUs
            lastPresentationUs = next
            return NormalizedVideoTimestamp(next, currentUnit())
        }
        if (rawTimestamp < 0) throw AasdkProtocolException("negative video timestamp")

        val previousRaw = lastRaw
        if (previousRaw == null) {
            lastRaw = rawTimestamp
            val firstPresentationUs = if (lastPresentationUs < 0) {
                0L
            } else {
                lastPresentationUs + fallbackFrameDurationUs
            }
            lastPresentationUs = firstPresentationUs
            return NormalizedVideoTimestamp(firstPresentationUs, VideoTimestampUnit.UNKNOWN)
        }
        if (rawTimestamp <= previousRaw) {
            throw AasdkProtocolException("video timestamp is not monotonic")
        }

        val rawDelta = rawTimestamp - previousRaw
        if (divisor == null) divisor = inferDivisor(rawDelta)
        val presentationDeltaUs = divisor
            ?.let { rawDelta / it }
            ?.takeIf { it > 0L }
            ?: fallbackFrameDurationUs
        val normalized = lastPresentationUs + presentationDeltaUs
        lastRaw = rawTimestamp
        lastPresentationUs = normalized
        return NormalizedVideoTimestamp(normalized, currentUnit())
    }

    fun reset() {
        lastRaw = null
        divisor = null
        lastPresentationUs = -1L
    }

    private fun inferDivisor(rawDelta: Long): Long? {
        val minimumContinuousUs = (fallbackFrameDurationUs / CONTINUOUS_DELTA_FACTOR).coerceAtLeast(1L)
        val maximumContinuousUs = fallbackFrameDurationUs * CONTINUOUS_DELTA_FACTOR
        if (rawDelta in minimumContinuousUs..maximumContinuousUs) return 1L

        val minimumContinuousNs = minimumContinuousUs * NANOS_PER_MICROSECOND
        val maximumContinuousNs = maximumContinuousUs * NANOS_PER_MICROSECOND
        if (rawDelta in minimumContinuousNs..maximumContinuousNs) return NANOS_PER_MICROSECOND
        return null
    }

    private fun currentUnit(): VideoTimestampUnit = when (divisor) {
        null -> VideoTimestampUnit.UNKNOWN
        1L -> VideoTimestampUnit.MICROSECONDS
        else -> VideoTimestampUnit.NANOSECONDS
    }

    private companion object {
        // Lock the unit only from a frame-like delta. A long startup gap must
        // not make a microsecond stream look like nanoseconds.
        const val CONTINUOUS_DELTA_FACTOR = 4L
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}
