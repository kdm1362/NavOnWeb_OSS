/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.audio

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
            else -> interleavedPcm.copyOf()
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

    private var sourceBuffer = ShortArray(0)
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
            sourceBuffer = ShortArray(0)
            return ShortArray(0)
        }
        val finalOutputFrameCount = scaleFrameCountCeil(totalInputFrames)
        val output = drain(finalOutputFrameCount)
        check(nextOutputFrame == finalOutputFrameCount) {
            "resampler did not produce the expected final frame count"
        }
        sourceBuffer = ShortArray(0)
        bufferStartFrame = totalInputFrames
        return output
    }

    fun reset() {
        sourceBuffer = ShortArray(0)
        bufferStartFrame = 0L
        totalInputFrames = 0L
        nextOutputFrame = 0L
        finished = false
    }

    private fun append(samples: ShortArray) {
        if (sourceBuffer.isEmpty()) {
            sourceBuffer = samples.copyOf()
            return
        }
        val combined = ShortArray(sourceBuffer.size + samples.size)
        sourceBuffer.copyInto(combined)
        samples.copyInto(combined, destinationOffset = sourceBuffer.size)
        sourceBuffer = combined
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
        check(relativeFrame >= 0L && relativeFrame < sourceBuffer.size / channelCount) {
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
        if (sourceBuffer.isEmpty()) return
        val nextPositionNumerator = nextOutputFrame * sourceStepNumerator.toLong()
        val nextRequiredFrame = nextPositionNumerator / positionDenominator
        val dropUntilFrame = minOf(nextRequiredFrame, totalInputFrames)
        val framesToDrop = dropUntilFrame - bufferStartFrame
        if (framesToDrop <= 0L) return
        val samplesToDrop = framesToDrop * channelCount
        check(samplesToDrop <= sourceBuffer.size)
        sourceBuffer = sourceBuffer.copyOfRange(samplesToDrop.toInt(), sourceBuffer.size)
        bufferStartFrame = dropUntilFrame
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
