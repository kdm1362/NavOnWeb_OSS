/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.webrtc

import java.util.Locale
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

/** Compact sender diagnostics used to distinguish idle screen compression from quality loss. */
internal data class WebRtcOutboundVideoStats(
    val timestampUs: Double,
    val bytesSent: Long,
    val framesEncoded: Long,
    val frameWidth: Int?,
    val frameHeight: Int?,
    val framesPerSecond: Double?,
    val qpSum: Long?,
    val targetBitrateBps: Long?,
    val availableOutgoingBitrateBps: Long?,
    val qualityLimitationReason: String?,
    val packetsLost: Long?,
    val roundTripTimeSeconds: Double?,
    val codecMimeType: String?,
    val encoderImplementation: String?,
) {
    fun logSummary(previous: WebRtcOutboundVideoStats?): String {
        val elapsedSeconds = previous
            ?.let { (timestampUs - it.timestampUs) / MICROSECONDS_PER_SECOND }
            ?.takeIf { it > 0.0 }
        val sentBitrateBps = elapsedSeconds?.let { elapsed ->
            ((bytesSent - previous.bytesSent).coerceAtLeast(0L) * 8.0 / elapsed).toLong()
        }
        val encodedFrames = previous?.let { (framesEncoded - it.framesEncoded).coerceAtLeast(0L) }
        val averageQp = if (previous?.qpSum != null && qpSum != null && encodedFrames != null && encodedFrames > 0L) {
            (qpSum - previous.qpSum).coerceAtLeast(0L).toDouble() / encodedFrames
        } else {
            null
        }
        val size = if (frameWidth != null && frameHeight != null) "${frameWidth}x$frameHeight" else "unknown"
        return buildString {
            append("size=").append(size)
            append(" fps=").append(framesPerSecond.formatOneDecimal())
            append(" sendMbps=").append(sentBitrateBps.toMegabitsPerSecond())
            append(" targetMbps=").append(targetBitrateBps.toMegabitsPerSecond())
            append(" availableMbps=").append(availableOutgoingBitrateBps.toMegabitsPerSecond())
            append(" avgQp=").append(averageQp.formatOneDecimal())
            append(" limitation=").append(qualityLimitationReason ?: "unknown")
            append(" lost=").append(packetsLost ?: -1L)
            append(" rttMs=").append(roundTripTimeSeconds?.times(1_000.0).formatOneDecimal())
            append(" codec=").append(codecMimeType ?: "unknown")
            append(" encoder=").append(encoderImplementation ?: "unknown")
        }
    }

    private fun Long?.toMegabitsPerSecond(): String =
        this?.let { String.format(Locale.US, "%.2f", it / 1_000_000.0) } ?: "unknown"

    private fun Double?.formatOneDecimal(): String =
        this?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"

    private companion object {
        const val MICROSECONDS_PER_SECOND = 1_000_000.0
    }
}

internal object WebRtcOutboundVideoStatsParser {
    fun parse(report: RTCStatsReport): WebRtcOutboundVideoStats? {
        val stats = report.statsMap
        val outbound = stats.values.firstOrNull { entry ->
            entry.type == "outbound-rtp" && entry.members.isVideo()
        } ?: return null
        val codec = outbound.members.string("codecId")?.let(stats::get)
        val selectedPair = stats.values.firstOrNull { entry ->
            entry.type == "candidate-pair" &&
                entry.members.string("state") == "succeeded" &&
                (entry.members.boolean("selected") || entry.members.boolean("nominated"))
        }
        val remoteInbound = stats.values.firstOrNull { entry ->
            entry.type == "remote-inbound-rtp" && entry.members.isVideo()
        }
        return WebRtcOutboundVideoStats(
            timestampUs = report.timestampUs,
            bytesSent = outbound.members.long("bytesSent") ?: 0L,
            framesEncoded = outbound.members.long("framesEncoded") ?: 0L,
            frameWidth = outbound.members.int("frameWidth"),
            frameHeight = outbound.members.int("frameHeight"),
            framesPerSecond = outbound.members.double("framesPerSecond"),
            qpSum = outbound.members.long("qpSum"),
            targetBitrateBps = outbound.members.long("targetBitrate"),
            availableOutgoingBitrateBps = selectedPair?.members?.long("availableOutgoingBitrate"),
            qualityLimitationReason = outbound.members.string("qualityLimitationReason"),
            packetsLost = remoteInbound?.members?.long("packetsLost"),
            roundTripTimeSeconds = remoteInbound?.members?.double("roundTripTime")
                ?: selectedPair?.members?.double("currentRoundTripTime"),
            codecMimeType = codec?.members?.string("mimeType"),
            encoderImplementation = outbound.members.string("encoderImplementation"),
        )
    }

    private fun Map<String, Any>.isVideo(): Boolean =
        string("kind") == "video" || string("mediaType") == "video"

    private fun Map<String, Any>.string(key: String): String? = get(key) as? String

    private fun Map<String, Any>.boolean(key: String): Boolean = get(key) as? Boolean ?: false

    private fun Map<String, Any>.long(key: String): Long? = (get(key) as? Number)?.toLong()

    private fun Map<String, Any>.int(key: String): Int? = (get(key) as? Number)?.toInt()

    private fun Map<String, Any>.double(key: String): Double? = (get(key) as? Number)?.toDouble()
}
