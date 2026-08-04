/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPcm16ProcessorTest {
    @Test
    fun freePolicyForcesMonoSixteenKilohertz() {
        val input = Pcm16Format(sampleRateHz = 48_000, channelCount = 2)

        val policy = Pcm16AudioPolicy.resolve(AudioStreamAccessTier.FREE, input)

        assertEquals(Pcm16AudioPolicy.FREE_SAMPLE_RATE_HZ, 16_000)
        assertEquals(Pcm16Format(16_000, 1), policy.outputFormat)
        assertTrue(policy.downmixToMono)
    }

    @Test
    fun premiumPolicyPreservesSourceFormat() {
        val stereo = Pcm16Format(sampleRateHz = 44_100, channelCount = 2)
        val mono = Pcm16Format(sampleRateHz = 96_000, channelCount = 1)

        assertEquals(
            stereo,
            Pcm16AudioPolicy.resolve(AudioStreamAccessTier.PREMIUM, stereo).outputFormat,
        )
        assertEquals(
            mono,
            Pcm16AudioPolicy.resolve(AudioStreamAccessTier.PREMIUM, mono).outputFormat,
        )
    }

    @Test
    fun formatRejectsUnsupportedBoundaries() {
        assertEquals(Pcm16Format(8_000, 1), Pcm16Format(8_000, 1))
        assertEquals(Pcm16Format(192_000, 2), Pcm16Format(192_000, 2))
        assertThrows(IllegalArgumentException::class.java) { Pcm16Format(7_999, 1) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16Format(192_001, 1) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16Format(48_000, 0) }
        assertThrows(IllegalArgumentException::class.java) { Pcm16Format(48_000, 3) }
    }

    @Test
    fun premiumStereoIsBitExactAcrossChunks() {
        val processor = StreamingPcm16Processor(
            accessTier = AudioStreamAccessTier.PREMIUM,
            inputFormat = Pcm16Format(48_000, 2),
        )
        val first = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, -12_345, 12_345)
        val second = shortArrayOf(-1, 1, 0, 0)

        assertArrayEquals(first, processor.process(first))
        assertArrayEquals(second, processor.process(second))
        assertArrayEquals(ShortArray(0), processor.finish())
        assertEquals(4L, processor.inputFramesAccepted)
        assertEquals(4L, processor.outputFramesProduced)
    }

    @Test
    fun freeStereoDownmixDoesNotClipAtPcmLimits() {
        val processor = StreamingPcm16Processor(
            accessTier = AudioStreamAccessTier.FREE,
            inputFormat = Pcm16Format(16_000, 2),
        )

        val output = processor.process(
            shortArrayOf(
                Short.MAX_VALUE,
                Short.MAX_VALUE,
                Short.MIN_VALUE,
                Short.MIN_VALUE,
                Short.MAX_VALUE,
                Short.MIN_VALUE,
            ),
        )

        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0), output)
        assertArrayEquals(ShortArray(0), processor.finish())
    }

    @Test
    fun freeUpsamplingUsesLinearInterpolationAndBoundedFinalHold() {
        val processor = StreamingPcm16Processor(
            accessTier = AudioStreamAccessTier.FREE,
            inputFormat = Pcm16Format(8_000, 1),
        )

        val streamed = processor.process(shortArrayOf(0, 1_000))
        val tail = processor.finish()

        assertArrayEquals(shortArrayOf(0, 500, 1_000), streamed)
        assertArrayEquals(shortArrayOf(1_000), tail)
        assertEquals(4L, processor.outputFramesProduced)
    }

    @Test
    fun resamplingAndDownmixAreIndependentOfChunkBoundaries() {
        val format = Pcm16Format(sampleRateHz = 44_100, channelCount = 2)
        val frames = 997
        val input = ShortArray(frames * 2) { sampleIndex ->
            val frame = sampleIndex / 2
            if (sampleIndex % 2 == 0) {
                ((frame * 97) % 60_000 - 30_000).toShort()
            } else {
                ((frame * 53) % 50_000 - 25_000).toShort()
            }
        }
        val oneShot = transform(
            tier = AudioStreamAccessTier.FREE,
            format = format,
            chunks = listOf(input),
        )
        val chunks = splitFrames(input, channelCount = 2, frameCounts = listOf(1, 7, 2, 113, 3, 256, 17))
        val chunked = transform(
            tier = AudioStreamAccessTier.FREE,
            format = format,
            chunks = chunks,
        )

        assertArrayEquals(oneShot, chunked)
        assertEquals(ceilScale(frames, 16_000, 44_100), chunked.size)
    }

    @Test
    fun stereoChannelsRemainIndependentDuringPremiumResamplingBypass() {
        val input = shortArrayOf(1_000, -1_000, 2_000, -2_000, 3_000, -3_000)
        val output = transform(
            tier = AudioStreamAccessTier.PREMIUM,
            format = Pcm16Format(96_000, 2),
            chunks = listOf(input.copyOfRange(0, 2), input.copyOfRange(2, input.size)),
        )

        assertArrayEquals(input, output)
    }

    @Test
    fun invalidPartialFrameDoesNotMutateStreamingState() {
        val format = Pcm16Format(48_000, 2)
        val processor = StreamingPcm16Processor(AudioStreamAccessTier.FREE, format)
        assertThrows(IllegalArgumentException::class.java) {
            processor.process(shortArrayOf(1, 2, 3))
        }
        assertEquals(0L, processor.inputFramesAccepted)

        val valid = shortArrayOf(1_000, 3_000, 2_000, 4_000, 3_000, 5_000, 4_000, 6_000)
        val actual = concatenate(processor.process(valid), processor.finish())
        val fresh = transform(AudioStreamAccessTier.FREE, format, listOf(valid))

        assertArrayEquals(fresh, actual)
    }

    @Test
    fun resetStartsANewIndependentStream() {
        val processor = StreamingPcm16Processor(
            AudioStreamAccessTier.FREE,
            Pcm16Format(8_000, 1),
        )
        val first = concatenate(processor.process(shortArrayOf(100, 300)), processor.finish())

        processor.reset()
        val second = concatenate(processor.process(shortArrayOf(100, 300)), processor.finish())

        assertArrayEquals(first, second)
        assertEquals(2L, processor.inputFramesAccepted)
        assertEquals(4L, processor.outputFramesProduced)
    }

    @Test
    fun finishAndProcessRejectUseAfterFinish() {
        val processor = StreamingPcm16Processor(
            AudioStreamAccessTier.FREE,
            Pcm16Format(16_000, 1),
        )
        assertArrayEquals(ShortArray(0), processor.finish())

        assertThrows(IllegalStateException::class.java) { processor.finish() }
        assertThrows(IllegalStateException::class.java) { processor.process(shortArrayOf(1)) }
    }

    @Test
    fun interpolatedExtremeSamplesStayInsidePcm16Range() {
        val output = transform(
            tier = AudioStreamAccessTier.FREE,
            format = Pcm16Format(8_000, 1),
            chunks = listOf(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)),
        )

        assertArrayEquals(
            shortArrayOf(Short.MIN_VALUE, -1, Short.MAX_VALUE, Short.MAX_VALUE),
            output,
        )
        assertFalse(output.any { it.toInt() !in Short.MIN_VALUE..Short.MAX_VALUE })
    }

    private fun transform(
        tier: AudioStreamAccessTier,
        format: Pcm16Format,
        chunks: List<ShortArray>,
    ): ShortArray {
        val processor = StreamingPcm16Processor(tier, format)
        val outputs = chunks.map(processor::process) + processor.finish()
        return concatenate(*outputs.toTypedArray())
    }

    private fun splitFrames(
        input: ShortArray,
        channelCount: Int,
        frameCounts: List<Int>,
    ): List<ShortArray> {
        val chunks = mutableListOf<ShortArray>()
        var sourceFrame = 0
        var sizeIndex = 0
        val totalFrames = input.size / channelCount
        while (sourceFrame < totalFrames) {
            val requested = frameCounts[sizeIndex % frameCounts.size]
            val frames = minOf(requested, totalFrames - sourceFrame)
            val start = sourceFrame * channelCount
            val end = (sourceFrame + frames) * channelCount
            chunks += input.copyOfRange(start, end)
            sourceFrame += frames
            sizeIndex += 1
        }
        return chunks
    }

    private fun concatenate(vararg arrays: ShortArray): ShortArray {
        val result = ShortArray(arrays.sumOf { it.size })
        var offset = 0
        arrays.forEach { array ->
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }

    private fun ceilScale(frames: Int, outputRate: Int, inputRate: Int): Int =
        (frames.toLong() * outputRate + inputRate - 1L).div(inputRate).toInt()
}
