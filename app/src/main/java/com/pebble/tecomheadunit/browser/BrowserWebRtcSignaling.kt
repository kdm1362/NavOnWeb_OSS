/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser

import java.security.MessageDigest
import java.util.Base64
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
    /** SHA-256 identifier of the already-authenticated browser credential; never the secret. */
    val ownerKey: String,
    /** Public, revocable paired-device identifier. It is safe to return to authenticated peers. */
    val deviceId: String = legacyBrowserDeviceId(ownerKey),
    val accessMode: BrowserSessionAccessMode = BrowserSessionAccessMode.CONTROL,
    /** Stable paired-device palette slot. The browser owns the actual color palette. */
    val colorSlot: Int = 0,
    val displayName: String = "",
) {
    init {
        require(OWNER_KEY_PATTERN.matches(ownerKey)) { "invalid WebRTC session owner key" }
        require(DEVICE_ID_PATTERN.matches(deviceId)) { "invalid paired-device ID" }
        require(colorSlot in 0 until MAX_SESSION_COLOR_SLOTS) { "invalid session color slot" }
        require(displayName.codePointCount(0, displayName.length) <= MAX_DISPLAY_NAME_CODE_POINTS) {
            "paired-device display name is too long"
        }
    }

    private companion object {
        val OWNER_KEY_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,64}$")
        const val MAX_DISPLAY_NAME_CODE_POINTS = 64
    }
}

enum class BrowserSessionAccessMode(val wireName: String) {
    CONTROL("control"),
    READ_ONLY("read_only"),
}

internal const val MAX_PREMIUM_BROWSER_SESSIONS = 3
internal const val MAX_FREE_BROWSER_SESSIONS = 1
internal const val MAX_SESSION_COLOR_SLOTS = 3

/** Used only by legacy host tests/callers that have not supplied registry metadata yet. */
private fun legacyBrowserDeviceId(ownerKey: String): String = "legacy_${ownerKey.take(24)}"

internal fun browserWebRtcOwnerKey(browserCredential: String): String {
    require(browserCredential.isNotBlank()) { "browser credential is required" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(browserCredential.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal sealed interface BrowserWebRtcSessionAdmission {
    data object Open : BrowserWebRtcSessionAdmission
    data object Busy : BrowserWebRtcSessionAdmission
    data class Replace(val sessionId: String) : BrowserWebRtcSessionAdmission
}

internal fun browserWebRtcSessionAdmission(
    activeSessionId: String?,
    activeOwnerKey: String?,
    requestedOwnerKey: String,
): BrowserWebRtcSessionAdmission = when {
    activeSessionId == null -> BrowserWebRtcSessionAdmission.Open
    activeOwnerKey == requestedOwnerKey -> BrowserWebRtcSessionAdmission.Replace(activeSessionId)
    else -> BrowserWebRtcSessionAdmission.Busy
}

internal fun browserWebRtcSessionCloseMatches(activeSessionId: String?, requestedSessionId: String): Boolean =
    activeSessionId == requestedSessionId

internal fun browserWebRtcSessionLimit(isPremium: Boolean): Int =
    if (isPremium) MAX_PREMIUM_BROWSER_SESSIONS else MAX_FREE_BROWSER_SESSIONS

internal fun browserWebRtcEffectiveColorSlot(
    requestedSlot: Int,
    usedSlots: Set<Int>,
): Int? {
    require(requestedSlot in 0 until MAX_SESSION_COLOR_SLOTS)
    require(usedSlots.all { it in 0 until MAX_SESSION_COLOR_SLOTS })
    return requestedSlot.takeIf { it !in usedSlots }
        ?: (0 until MAX_SESSION_COLOR_SLOTS).firstOrNull { it !in usedSlots }
}

internal fun browserWebRtcSessionAdmission(
    activeOwnersBySessionId: Map<String, String>,
    requestedOwnerKey: String,
    maximumSessions: Int,
): BrowserWebRtcSessionAdmission {
    require(maximumSessions in MAX_FREE_BROWSER_SESSIONS..MAX_PREMIUM_BROWSER_SESSIONS) {
        "invalid browser session limit"
    }
    val existing = activeOwnersBySessionId.entries.firstOrNull { it.value == requestedOwnerKey }
    return when {
        existing != null -> BrowserWebRtcSessionAdmission.Replace(existing.key)
        activeOwnersBySessionId.size < maximumSessions -> BrowserWebRtcSessionAdmission.Open
        else -> BrowserWebRtcSessionAdmission.Busy
    }
}

/**
 * Deterministic downgrade policy. The preferred/live main survives when present; otherwise the
 * oldest active interactive session survives. Read-only sessions are never chosen ahead of an
 * interactive session because they cannot own projection input or viewport geometry.
 */
internal fun browserWebRtcSessionsToCloseForLimit(
    sessions: List<BrowserWebRtcSessionPublicMetadata>,
    maximumSessions: Int,
    preferredMainDeviceId: String?,
): List<String> {
    require(maximumSessions in MAX_FREE_BROWSER_SESSIONS..MAX_PREMIUM_BROWSER_SESSIONS)
    if (sessions.size <= maximumSessions) return emptyList()
    val orderedKeep = sessions.sortedWith(
        compareByDescending<BrowserWebRtcSessionPublicMetadata> {
            preferredMainDeviceId != null && it.deviceId == preferredMainDeviceId
        }
            .thenByDescending { it.accessMode == BrowserSessionAccessMode.CONTROL }
            .thenBy { it.connectedSequence }
            .thenBy { it.sessionId },
    ).take(maximumSessions).mapTo(mutableSetOf()) { it.sessionId }
    return sessions
        .filterNot { it.sessionId in orderedKeep }
        .sortedWith(compareBy<BrowserWebRtcSessionPublicMetadata> { it.connectedSequence }.thenBy { it.sessionId })
        .map { it.sessionId }
}

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
    val publicMetadata: BrowserWebRtcSessionPublicMetadata? = null,
) {
    init {
        require(BrowserProbeServer.isValidWebRtcSessionId(sessionId)) { "invalid WebRTC session ID" }
        require(selectedCodec != BrowserWebRtcCodec.AUTO) { "AUTO cannot be a negotiated codec" }
        require(state != BrowserWebRtcSessionState.READY || !answerSdp.isNullOrBlank()) {
            "a ready WebRTC session needs an SDP answer"
        }
    }
}

data class BrowserWebRtcSessionPublicMetadata(
    val sessionId: String,
    val deviceId: String,
    val accessMode: BrowserSessionAccessMode,
    val colorSlot: Int,
    val displayName: String = "",
    val isMain: Boolean = false,
    /** Monotonic controller-local ordering; never serialized to browsers. */
    internal val connectedSequence: Long = 0L,
) {
    init {
        require(BrowserProbeServer.isValidWebRtcSessionId(sessionId)) { "invalid WebRTC session ID" }
        require(deviceId.length in 16..64 && deviceId.all {
            it.isLetterOrDigit() || it == '-' || it == '_'
        }) { "invalid paired-device ID" }
        require(colorSlot in 0 until MAX_SESSION_COLOR_SLOTS) { "invalid session color slot" }
        require(displayName.codePointCount(0, displayName.length) <= 64) {
            "paired-device display name is too long"
        }
    }
}

/** Returns the same snapshot unless this live public identity belongs to [deviceId]. */
internal fun BrowserWebRtcSessionSnapshot.withDeviceDisplayName(
    deviceId: String,
    displayName: String,
): BrowserWebRtcSessionSnapshot {
    require(displayName.isNotBlank()) { "paired-device display name is blank" }
    require(displayName.none(Char::isISOControl)) {
        "paired-device display name has control characters"
    }
    val metadata = publicMetadata ?: return this
    if (metadata.deviceId != deviceId || metadata.displayName == displayName) return this
    return copy(publicMetadata = metadata.copy(displayName = displayName))
}

internal class BrowserWebRtcSessionTombstones(
    private val maximumEntries: Int = 16,
) {
    private data class Entry(
        val ownerKey: String,
        val snapshot: BrowserWebRtcSessionSnapshot,
    )

    private val entries = linkedMapOf<String, Entry>()

    init {
        require(maximumEntries > 0)
    }

    fun put(ownerKey: String, snapshot: BrowserWebRtcSessionSnapshot) {
        entries.remove(snapshot.sessionId)
        entries[snapshot.sessionId] = Entry(ownerKey, snapshot)
        while (entries.size > maximumEntries) {
            entries.remove(entries.keys.first())
        }
    }

    fun get(sessionId: String, ownerKey: String): BrowserWebRtcSessionSnapshot? =
        entries[sessionId]?.takeIf { it.ownerKey == ownerKey }?.snapshot

    fun remove(sessionId: String) {
        entries.remove(sessionId)
    }

    fun clear() = entries.clear()

    internal val size: Int
        get() = entries.size
}

data class BrowserTouchPresenceEvent(
    val sourceDeviceId: String,
    val colorSlot: Int,
    val phase: String,
    val x: Double,
    val y: Double,
    val sequence: Long,
) {
    init {
        require(sourceDeviceId.length in 16..64)
        require(colorSlot in 0 until MAX_SESSION_COLOR_SLOTS)
        require(phase in setOf("down", "move", "up", "cancel"))
        require(x.isFinite() && x in 0.0..1.0)
        require(y.isFinite() && y in 0.0..1.0)
        require(sequence >= 0L)
    }

    fun toJsonString(): String = buildString(192) {
        append("{\"type\":\"session_event\",\"event\":\"touch_presence\",\"sourceDeviceId\":")
        append(WebRtcEventJson.string(sourceDeviceId))
        append(",\"colorSlot\":").append(colorSlot)
        append(",\"phase\":").append(WebRtcEventJson.string(phase))
        append(",\"x\":").append(x)
        append(",\"y\":").append(y)
        append(",\"sequence\":").append(sequence)
        append('}')
    }
}

private object WebRtcEventJson {
    fun string(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (character < ' ') append(' ') else append(character)
            }
        }
        append('"')
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

    /** Owner-bound lookup. A mismatched owner must be indistinguishable from a missing session. */
    fun session(sessionId: String, ownerKey: String): BrowserWebRtcSessionSnapshot?

    /** Owner-bound close. A mismatched owner must not close or reveal another device's session. */
    fun closeSession(sessionId: String, ownerKey: String): Boolean

    /** Revokes every live peer belonging to one deleted paired device. */
    fun closeSessionsForOwner(ownerKey: String): Int

    fun closeSessionsForDevice(deviceId: String): Int

    /** Updates only the public name on live sessions; no peer or DataChannel may be closed. */
    fun updateDeviceDisplayName(deviceId: String, displayName: String): Int

    /** Returns only public metadata for the owner's current live session. */
    fun activeSessionMetadata(ownerKey: String): BrowserWebRtcSessionPublicMetadata?

    fun oldestInteractiveDeviceId(): String?

    fun connectedDeviceIds(): Set<String>

    fun connectedSessionMetadata(): List<BrowserWebRtcSessionPublicMetadata>

    /** Pushes a local-only touch marker to every live peer except the originating owner. */
    fun publishTouchPresence(sourceOwnerKey: String, event: BrowserTouchPresenceEvent): Int

    /** Applies entitlement loss immediately and returns the number of peers closed. */
    fun reconcileSessionLimit(maximumSessions: Int, preferredMainDeviceId: String?): Int

    data object Unavailable : BrowserWebRtcSignaling {
        override fun capabilities(): BrowserWebRtcCapabilities = BrowserWebRtcCapabilities(
            available = false,
            detail = "Native WebRTC sender is not connected",
        )

        override fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult =
            BrowserWebRtcOpenResult.Rejected("Native WebRTC sender is not connected")

        override fun session(sessionId: String, ownerKey: String): BrowserWebRtcSessionSnapshot? = null

        override fun closeSession(sessionId: String, ownerKey: String): Boolean = false

        override fun closeSessionsForOwner(ownerKey: String): Int = 0

        override fun closeSessionsForDevice(deviceId: String): Int = 0

        override fun updateDeviceDisplayName(deviceId: String, displayName: String): Int = 0

        override fun activeSessionMetadata(ownerKey: String): BrowserWebRtcSessionPublicMetadata? = null

        override fun oldestInteractiveDeviceId(): String? = null

        override fun connectedDeviceIds(): Set<String> = emptySet()

        override fun connectedSessionMetadata(): List<BrowserWebRtcSessionPublicMetadata> = emptyList()

        override fun publishTouchPresence(
            sourceOwnerKey: String,
            event: BrowserTouchPresenceEvent,
        ): Int = 0

        override fun reconcileSessionLimit(maximumSessions: Int, preferredMainDeviceId: String?): Int = 0
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

    fun replaceIfCurrent(
        expected: BrowserWebRtcSignaling,
        next: BrowserWebRtcSignaling,
    ): Boolean = delegate.compareAndSet(expected, next)

    override fun capabilities(): BrowserWebRtcCapabilities = delegate.get().capabilities()

    override fun openSession(offer: BrowserWebRtcOffer): BrowserWebRtcOpenResult =
        delegate.get().openSession(offer)

    override fun session(sessionId: String, ownerKey: String): BrowserWebRtcSessionSnapshot? =
        delegate.get().session(sessionId, ownerKey)

    override fun closeSession(sessionId: String, ownerKey: String): Boolean =
        delegate.get().closeSession(sessionId, ownerKey)

    override fun closeSessionsForOwner(ownerKey: String): Int =
        runCatching { delegate.get().closeSessionsForOwner(ownerKey) }.getOrDefault(0)

    override fun closeSessionsForDevice(deviceId: String): Int =
        runCatching { delegate.get().closeSessionsForDevice(deviceId) }.getOrDefault(0)

    override fun updateDeviceDisplayName(deviceId: String, displayName: String): Int =
        runCatching {
            delegate.get().updateDeviceDisplayName(deviceId, displayName)
        }.getOrDefault(0)

    override fun activeSessionMetadata(ownerKey: String): BrowserWebRtcSessionPublicMetadata? =
        runCatching { delegate.get().activeSessionMetadata(ownerKey) }.getOrNull()

    override fun oldestInteractiveDeviceId(): String? =
        runCatching { delegate.get().oldestInteractiveDeviceId() }.getOrNull()

    override fun connectedDeviceIds(): Set<String> =
        runCatching { delegate.get().connectedDeviceIds() }.getOrDefault(emptySet())

    override fun connectedSessionMetadata(): List<BrowserWebRtcSessionPublicMetadata> =
        runCatching { delegate.get().connectedSessionMetadata() }.getOrDefault(emptyList())

    override fun publishTouchPresence(
        sourceOwnerKey: String,
        event: BrowserTouchPresenceEvent,
    ): Int = runCatching {
        delegate.get().publishTouchPresence(sourceOwnerKey, event)
    }.getOrDefault(0)

    override fun reconcileSessionLimit(maximumSessions: Int, preferredMainDeviceId: String?): Int =
        runCatching {
            delegate.get().reconcileSessionLimit(maximumSessions, preferredMainDeviceId)
        }.getOrDefault(0)
}
