/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.audio

/** Closed server-owned access decision. Clients cannot supply arbitrary DSP parameters. */
enum class AudioStreamAccessTier {
    FREE,
    PREMIUM,
}

/** Interleaved signed little-endian-equivalent PCM16 sample layout. */
data class Pcm16Format(
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    init {
        require(sampleRateHz in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            "sampleRateHz must be between $MIN_SAMPLE_RATE_HZ and $MAX_SAMPLE_RATE_HZ"
        }
        require(channelCount in 1..MAX_CHANNEL_COUNT) {
            "only mono and stereo PCM are supported"
        }
    }

    companion object {
        const val MIN_SAMPLE_RATE_HZ = 8_000
        const val MAX_SAMPLE_RATE_HZ = 192_000
        const val MAX_CHANNEL_COUNT = 2
    }
}

class ResolvedPcm16Policy private constructor(
    val accessTier: AudioStreamAccessTier,
    val inputFormat: Pcm16Format,
    val outputFormat: Pcm16Format,
    val downmixToMono: Boolean,
) {
    internal companion object {
        fun free(inputFormat: Pcm16Format): ResolvedPcm16Policy =
            ResolvedPcm16Policy(
                accessTier = AudioStreamAccessTier.FREE,
                inputFormat = inputFormat,
                outputFormat = Pcm16Format(Pcm16AudioPolicy.FREE_SAMPLE_RATE_HZ, 1),
                downmixToMono = inputFormat.channelCount == 2,
            )

        fun premium(inputFormat: Pcm16Format): ResolvedPcm16Policy =
            ResolvedPcm16Policy(
                accessTier = AudioStreamAccessTier.PREMIUM,
                inputFormat = inputFormat,
                outputFormat = inputFormat,
                downmixToMono = false,
            )
    }
}

/**
 * Server-enforced policy resolver.
 *
 * FREE always emits mono 16 kHz PCM. 16 kHz is an explicit voice-quality default that reduces
 * sample bandwidth to one sixth of common 48 kHz stereo input while remaining sufficient for
 * navigation prompts and speech. PREMIUM preserves the original mono/stereo channel layout and
 * source sample rate exactly.
 */
object Pcm16AudioPolicy {
    const val FREE_SAMPLE_RATE_HZ = 16_000

    fun resolve(
        accessTier: AudioStreamAccessTier,
        inputFormat: Pcm16Format,
    ): ResolvedPcm16Policy = when (accessTier) {
        AudioStreamAccessTier.FREE ->
            ResolvedPcm16Policy.free(inputFormat)
        AudioStreamAccessTier.PREMIUM ->
            ResolvedPcm16Policy.premium(inputFormat)
    }
}
