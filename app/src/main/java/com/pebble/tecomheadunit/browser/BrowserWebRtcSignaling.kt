/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import java.util.concurrent.atomic.AtomicReference

/** Closed codec preference used by the phone-controlled WebRTC policy. */
enum class BrowserWebRtcCodec(val wireName: String) {
    AUTO("auto"),
    H264("h264"),
    VP8("vp8"),
    VP9("vp9"),
    AV1("av1"),
    ;

    companion object {
        fun fromWireName(value: String?): BrowserWebRtcCodec? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

data class BrowserWebRtcIceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {
    init {
        require(urls.isNotEmpty()) { "ICE server needs at least one URL" }
        require(urls.all { it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:") }) {
            "invalid ICE server URL"
        }
    }
}

data class BrowserWebRtcCapabilities(
    val available: Boolean,
    /** Native preference order, most desirable codec first. */
    val codecs: List<BrowserWebRtcCodec> = emptyList(),
    val iceServers: List<BrowserWebRtcIceServer> = emptyList(),
    /** PCM output tracks supported through `navonweb-audio-<track>-v1` data channels. */
    val outputAudioDataChannelsV1: List<String> = emptyList(),
    /** Reliable, ordered post-connect browser RPC over `navonweb-control-v1`. */
    val controlDataChannelV1: Boolean = false,
    val detail: String = "",
) {
    init {
        require(BrowserWebRtcCodec.AUTO !in codecs) { "AUTO is a preference, not an encoder capability" }
        require(codecs.distinct().size == codecs.size) { "duplicate WebRTC codec capability" }
        require(outputAudioDataChannelsV1.distinct().size == outputAudioDataChannelsV1.size) {
            "duplicate WebRTC output-audio capability"
        }
        require(outputAudioDataChannelsV1.all { it in setOf("media", "speech", "system") }) {
            "invalid WebRTC output-audio capability"
        }
    }
}

data class BrowserWebRtcOffer(
    val preferredCodec: BrowserWebRtcCodec,
    val sdp: String,
)

enum class BrowserWebRtcSessionState(val wireName: String) {
    PENDING("pending"),
    READY("ready"),
    FAILED("failed"),
    CLOSED("closed"),
}

data class BrowserWebRtcSessionSnapshot(
    val sessionId: String,
    val state: BrowserWebRtcSessionState,
    val selectedCodec: BrowserWebRtcCodec? = null,
    val answerSdp: String? = null,
    val detail: String = "",
) {
    init {
        require(BrowserProbeServer.isValidWebRtcSessionId(sessionId)) { "invalid WebRTC session ID" }
        require(selectedCodec != BrowserWebRtcCodec.AUTO) { "AUTO cannot be a negotiated codec" }
        require(state != BrowserWebRtcSessionState.READY || !answerSdp.isNullOrBlank()) {
            "a ready WebRTC session needs an SDP answer"
        }
    }
}

sealed interface BrowserWebRtcOpenResult {
    data class Accepted(val session: BrowserWebRtcSessionSnapshot) : BrowserWebRtcOpenResult
    data object Busy : BrowserWebRtcOpenResult
    data class Rejected(val detail: String) : BrowserWebRtcOpenResult
}

/**
 * Adapter between the small browser HTTP server and the native WebRTC owner.
 *
 * Signalling is intentionally non-trickle: each SDP offer/answer must already
 * contain its gathered ICE candidates. This keeps credentials out of URLs and
 * leaves PeerConnection lifetime and threading with the native implementation.
 */
interface BrowserWebRtcSignaling {
    fun capabilities(): BrowserWebRtcCapabilities

    fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult

    fun session(sessionId: String): BrowserWebRtcSessionSnapshot?

    fun closeSession(sessionId: String): Boolean

    /** Revokes media owned by the credential that was replaced by a successful re-pair. */
    fun revokeActiveSessionForCredentialChange(): Boolean

    data object Unavailable : BrowserWebRtcSignaling {
        override fun capabilities(): BrowserWebRtcCapabilities = BrowserWebRtcCapabilities(
            available = false,
            detail = "Native WebRTC sender is not connected",
        )

        override fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult =
            BrowserWebRtcOpenResult.Rejected("Native WebRTC sender is not connected")

        override fun session(sessionId: String): BrowserWebRtcSessionSnapshot? = null

        override fun closeSession(sessionId: String): Boolean = false

        override fun revokeActiveSessionForCredentialChange(): Boolean = false
    }
}

/**
 * Keeps the HTTP server stable while ProjectionService replaces the resolution-bound native
 * WebRTC sender. Calls that begin after [replace] always reach the new sender.
 */
internal class SwitchableBrowserWebRtcSignaling(
    initial: BrowserWebRtcSignaling = BrowserWebRtcSignaling.Unavailable,
) : BrowserWebRtcSignaling {
    private val delegate = AtomicReference(initial)

    fun replace(next: BrowserWebRtcSignaling): BrowserWebRtcSignaling =
        delegate.getAndSet(next)

    override fun capabilities(): BrowserWebRtcCapabilities = delegate.get().capabilities()

    override fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult =
        delegate.get().openSession(offer)

    override fun session(sessionId: String): BrowserWebRtcSessionSnapshot? =
        delegate.get().session(sessionId)

    override fun closeSession(sessionId: String): Boolean =
        delegate.get().closeSession(sessionId)

    override fun revokeActiveSessionForCredentialChange(): Boolean =
        runCatching { delegate.get().revokeActiveSessionForCredentialChange() }.getOrDefault(false)
}
