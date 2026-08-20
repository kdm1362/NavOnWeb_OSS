/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.audio

/**
 * Stateful interleaved PCM16 transformer. One instance owns exactly one logical audio stream.
 * Calls must be serialized; use [reset] before reusing it for a new stream.
 */
class StreamingPcm16Processor(
    accessTier: AudioStreamAccessTier,
    val inputFormat: Pcm16Format,
) {
    val policy: ResolvedPcm16Policy = Pcm16AudioPolicy.resolve(accessTier, inputFormat)
    val outputFormat: Pcm16Format = policy.outputFormat

    var inputFramesAccepted: Long = 0L
        private set
    var outputFramesProduced: Long = 0L
        private set

    private var finished = false
    private val resampler = StreamingLinearPcm16Resampler(
        inputSampleRateHz = inputFormat.sampleRateHz,
        outputSampleRateHz = outputFormat.sampleRateHz,
        channelCount = outputFormat.channelCount,
    )

    /** Accepts complete interleaved frames and returns zero or more complete output frames. */
    fun process(interleavedPcm: ShortArray): ShortArray {
        check(!finished) { "stream is already finished" }
        require(interleavedPcm.size % inputFormat.channelCount == 0) {
            "PCM chunk does not contain complete input frames"
        }
        if (interleavedPcm.isEmpty()) return ShortArray(0)

        val inputFrameCount = interleavedPcm.size / inputFormat.channelCount
        val policySamples = when {
            policy.downmixToMono -> downmixStereoToMono(interleavedPcm)
            // No defensive copy is needed: the resampler never retains or mutates its
            // argument. Its general path copies the samples into its own source buffer,
            // and its 1:1 fast path returns a freshly allocated copy, so the caller's
            // array stays untouched and is never aliased by the returned output.
            else -> interleavedPcm
        }
        val output = resampler.process(policySamples)
        inputFramesAccepted += inputFrameCount.toLong()
        outputFramesProduced += output.size / outputFormat.channelCount
        return output
    }

    /** Flushes the final fractional sample using a bounded hold of the last source frame. */
    fun finish(): ShortArray {
        check(!finished) { "stream is already finished" }
        finished = true
        val output = resampler.finish()
        outputFramesProduced += output.size / outputFormat.channelCount
        return output
    }

    fun reset() {
        finished = false
        inputFramesAccepted = 0L
        outputFramesProduced = 0L
        resampler.reset()
    }
}

/** Equal-power is not used here: an arithmetic mean guarantees full-scale stereo cannot clip. */
private fun downmixStereoToMono(stereo: ShortArray): ShortArray {
    require(stereo.size % 2 == 0) { "stereo PCM chunk is not frame-aligned" }
    val mono = ShortArray(stereo.size / 2)
    var source = 0
    for (destination in mono.indices) {
        val sum = stereo[source].toInt() + stereo[source + 1].toInt()
        mono[destination] = clipPcm16((sum / 2).toLong())
        source += 2
    }
    return mono
}

/**
 * Streaming rational linear interpolator. It keeps only the source frame needed by the next
 * output position, so arbitrary input chunking produces the same samples as a single chunk.
 * Equal input and output rates take a copy-only fast path that skips buffering entirely; its
 * observable behavior (samples, counters, [finish]/[reset] semantics) matches the general path.
 */
internal class StreamingLinearPcm16Resampler(
    inputSampleRateHz: Int,
    outputSampleRateHz: Int,
    private val channelCount: Int,
) {
    private val divisor = greatestCommonDivisor(inputSampleRateHz, outputSampleRateHz)
    private val sourceStepNumerator = inputSampleRateHz / divisor
    private val positionDenominator = outputSampleRateHz / divisor
    private val outputCountNumerator = outputSampleRateHz / divisor
    private val outputCountDenominator = inputSampleRateHz / divisor

    /** Equal rates degenerate to the identity mapping; see the fast path in [process]. */
    private val passThrough = inputSampleRateHz == outputSampleRateHz

    /** Retained source samples live in [0, sourceLength); the rest of the array is capacity. */
    private var sourceBuffer = ShortArray(0)
    private var sourceLength = 0
    private var bufferStartFrame = 0L
    private var totalInputFrames = 0L
    private var nextOutputFrame = 0L
    private var finished = false

    init {
        require(inputSampleRateHz in Pcm16Format.MIN_SAMPLE_RATE_HZ..Pcm16Format.MAX_SAMPLE_RATE_HZ)
        require(outputSampleRateHz in Pcm16Format.MIN_SAMPLE_RATE_HZ..Pcm16Format.MAX_SAMPLE_RATE_HZ)
        require(channelCount in 1..Pcm16Format.MAX_CHANNEL_COUNT)
    }

    fun process(interleavedPcm: ShortArray): ShortArray {
        check(!finished) { "resampler is already finished" }
        require(interleavedPcm.size % channelCount == 0) {
            "PCM chunk does not contain complete resampler frames"
        }
        if (interleavedPcm.isEmpty()) return ShortArray(0)

        if (passThrough) {
            // 1:1 fast path. With equal rates every output frame lands exactly on one source
            // frame with zero fractional offset, so the general drain would emit an unmodified
            // copy of the input; produce that copy directly instead of buffering and
            // interpolating per sample. Returning a fresh array (never the caller's instance)
            // preserves the no-aliasing contract of the general path. The counters advance to
            // exactly the state the general path would reach for these rates: every input
            // frame is consumed and emitted immediately, so nothing stays buffered.
            val frameCount = (interleavedPcm.size / channelCount).toLong()
            totalInputFrames += frameCount
            nextOutputFrame += frameCount
            bufferStartFrame = totalInputFrames
            return interleavedPcm.copyOf()
        }

        append(interleavedPcm)
        totalInputFrames += interleavedPcm.size / channelCount
        val output = drain(finalOutputFrameCount = null)
        compactSourceBuffer()
        return output
    }

    fun finish(): ShortArray {
        check(!finished) { "resampler is already finished" }
        finished = true
        if (totalInputFrames == 0L) {
            releaseSourceBuffer()
            return ShortArray(0)
        }
        val finalOutputFrameCount = scaleFrameCountCeil(totalInputFrames)
        val output = drain(finalOutputFrameCount)
        check(nextOutputFrame == finalOutputFrameCount) {
            "resampler did not produce the expected final frame count"
        }
        releaseSourceBuffer()
        bufferStartFrame = totalInputFrames
        return output
    }

    fun reset() {
        releaseSourceBuffer()
        bufferStartFrame = 0L
        totalInputFrames = 0L
        nextOutputFrame = 0L
        finished = false
    }

    private fun append(samples: ShortArray) {
        val requiredLength = sourceLength + samples.size
        if (requiredLength > sourceBuffer.size) {
            // At-least-doubling growth keeps appends amortized O(1) per sample across a
            // stream. Once the capacity covers the largest chunk plus the few retained
            // boundary frames, steady-state appends allocate nothing at all.
            val grownCapacity = maxOf(requiredLength, sourceBuffer.size * 2)
            sourceBuffer = sourceBuffer.copyOf(grownCapacity)
        }
        samples.copyInto(sourceBuffer, destinationOffset = sourceLength)
        sourceLength = requiredLength
    }

    private fun drain(finalOutputFrameCount: Long?): ShortArray {
        val output = ShortArrayBuilder(channelCount * 64)
        while (finalOutputFrameCount == null || nextOutputFrame < finalOutputFrameCount) {
            val positionNumerator = nextOutputFrame * sourceStepNumerator.toLong()
            val sourceFrame = positionNumerator / positionDenominator
            val fractionNumerator = positionNumerator % positionDenominator
            if (sourceFrame >= totalInputFrames) break
            val nextSourceFrame = sourceFrame + 1L
            if (fractionNumerator != 0L &&
                nextSourceFrame >= totalInputFrames && finalOutputFrameCount == null
            ) {
                break
            }

            val upperFrame = if (nextSourceFrame < totalInputFrames) {
                nextSourceFrame
            } else {
                sourceFrame
            }
            for (channel in 0 until channelCount) {
                val lower = sampleAt(sourceFrame, channel)
                val upper = sampleAt(upperFrame, channel)
                output.add(interpolate(lower, upper, fractionNumerator))
            }
            nextOutputFrame += 1L
        }
        return output.toArray()
    }

    private fun sampleAt(frame: Long, channel: Int): Short {
        val relativeFrame = frame - bufferStartFrame
        check(relativeFrame >= 0L && relativeFrame < sourceLength / channelCount) {
            "required source frame is no longer buffered"
        }
        val sampleIndex = relativeFrame * channelCount + channel
        check(sampleIndex <= Int.MAX_VALUE)
        return sourceBuffer[sampleIndex.toInt()]
    }

    private fun interpolate(lower: Short, upper: Short, fractionNumerator: Long): Short {
        if (fractionNumerator == 0L || lower == upper) return lower
        val lowerWeight = positionDenominator.toLong() - fractionNumerator
        val weighted = lower.toLong() * lowerWeight + upper.toLong() * fractionNumerator
        return clipPcm16(divideRounded(weighted, positionDenominator.toLong()))
    }

    private fun compactSourceBuffer() {
        if (sourceLength == 0) return
        val nextPositionNumerator = nextOutputFrame * sourceStepNumerator.toLong()
        val nextRequiredFrame = nextPositionNumerator / positionDenominator
        val dropUntilFrame = minOf(nextRequiredFrame, totalInputFrames)
        val framesToDrop = dropUntilFrame - bufferStartFrame
        if (framesToDrop <= 0L) return
        val samplesToDrop = framesToDrop * channelCount
        check(samplesToDrop <= sourceLength)
        // Shift the retained tail (at most a couple of boundary frames) to the front of the
        // same buffer instead of reallocating. copyInto delegates to System.arraycopy, which
        // is specified to handle overlapping self-copies correctly.
        sourceBuffer.copyInto(
            sourceBuffer,
            destinationOffset = 0,
            startIndex = samplesToDrop.toInt(),
            endIndex = sourceLength,
        )
        sourceLength -= samplesToDrop.toInt()
        bufferStartFrame = dropUntilFrame
    }

    /** Drops the retained sample memory; used when a stream finishes or is reset. */
    private fun releaseSourceBuffer() {
        sourceBuffer = ShortArray(0)
        sourceLength = 0
    }

    private fun scaleFrameCountCeil(inputFrames: Long): Long {
        val whole = inputFrames / outputCountDenominator
        val remainder = inputFrames % outputCountDenominator
        val scaledWhole = Math.multiplyExact(whole, outputCountNumerator.toLong())
        val scaledRemainder = if (remainder == 0L) {
            0L
        } else {
            (remainder * outputCountNumerator + outputCountDenominator - 1L) /
                outputCountDenominator
        }
        return Math.addExact(scaledWhole, scaledRemainder)
    }
}

private class ShortArrayBuilder(initialCapacity: Int) {
    private var values = ShortArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun add(value: Short) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size] = value
        size += 1
    }

    fun toArray(): ShortArray = values.copyOf(size)
}

private fun clipPcm16(value: Long): Short =
    value.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()

private fun divideRounded(numerator: Long, denominator: Long): Long {
    require(denominator > 0L)
    return if (numerator >= 0L) {
        (numerator + denominator / 2L) / denominator
    } else {
        -((-numerator + denominator / 2L) / denominator)
    }
}

private fun greatestCommonDivisor(first: Int, second: Int): Int {
    var a = first
    var b = second
    while (b != 0) {
        val next = a % b
        a = b
        b = next
    }
    return a
}
