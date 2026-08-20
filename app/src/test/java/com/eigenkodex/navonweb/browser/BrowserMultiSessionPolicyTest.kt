package com.eigenkodex.navonweb.browser

import com.eigenkodex.navonweb.core.NormalizedTouch
import com.eigenkodex.navonweb.core.TouchPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMultiSessionPolicyTest {
    @Test
    fun `free admits one owner while premium admits three and same owner replaces`() {
        val owners = linkedMapOf(
            "session_000000001" to owner('a'),
            "session_000000002" to owner('b'),
            "session_000000003" to owner('c'),
        )

        assertEquals(
            BrowserWebRtcSessionAdmission.Busy,
            browserWebRtcSessionAdmission(
                activeOwnersBySessionId = owners.filterKeys { it == "session_000000001" },
                requestedOwnerKey = owner('b'),
                maximumSessions = MAX_FREE_BROWSER_SESSIONS,
            ),
        )
        assertEquals(
            BrowserWebRtcSessionAdmission.Replace("session_000000002"),
            browserWebRtcSessionAdmission(owners, owner('b'), MAX_PREMIUM_BROWSER_SESSIONS),
        )
        assertEquals(
            BrowserWebRtcSessionAdmission.Busy,
            browserWebRtcSessionAdmission(owners, owner('d'), MAX_PREMIUM_BROWSER_SESSIONS),
        )
        assertEquals(
            BrowserWebRtcSessionAdmission.Open,
            browserWebRtcSessionAdmission(
                activeOwnersBySessionId = owners - "session_000000003",
                requestedOwnerKey = owner('d'),
                maximumSessions = MAX_PREMIUM_BROWSER_SESSIONS,
            ),
        )
    }

    @Test
    fun `premium loss keeps preferred live main and otherwise oldest control session`() {
        val readOnly = metadata("session_000000001", "browser_0000000000000001", 1, 0, BrowserSessionAccessMode.READ_ONLY)
        val oldestControl = metadata("session_000000002", "browser_0000000000000002", 2, 1)
        val preferred = metadata("session_000000003", "browser_0000000000000003", 3, 2)
        val sessions = listOf(readOnly, oldestControl, preferred)

        assertEquals(
            listOf(readOnly.sessionId, oldestControl.sessionId),
            browserWebRtcSessionsToCloseForLimit(
                sessions,
                MAX_FREE_BROWSER_SESSIONS,
                preferred.deviceId,
            ),
        )
        assertEquals(
            setOf(readOnly.sessionId, preferred.sessionId),
            browserWebRtcSessionsToCloseForLimit(
                sessions,
                MAX_FREE_BROWSER_SESSIONS,
                preferredMainDeviceId = "browser_offline00000001",
            ).toSet(),
        )
    }

    @Test
    fun `three concurrent sessions receive distinct effective color slots`() {
        val first = checkNotNull(browserWebRtcEffectiveColorSlot(0, emptySet()))
        val second = checkNotNull(browserWebRtcEffectiveColorSlot(0, setOf(first)))
        val third = checkNotNull(browserWebRtcEffectiveColorSlot(1, setOf(first, second)))

        assertEquals(setOf(0, 1, 2), setOf(first, second, third))
        assertNull(browserWebRtcEffectiveColorSlot(2, setOf(0, 1, 2)))
    }

    @Test
    fun `terminal session churn remains bounded and owner bound`() {
        val tombstones = BrowserWebRtcSessionTombstones(maximumEntries = 16)
        repeat(100) { index ->
            val sessionId = "session_${index.toString().padStart(12, '0')}"
            tombstones.put(
                ownerKey = owner('a'),
                snapshot = BrowserWebRtcSessionSnapshot(
                    sessionId = sessionId,
                    state = BrowserWebRtcSessionState.FAILED,
                    detail = "failed",
                ),
            )
        }

        assertEquals(16, tombstones.size)
        assertNull(tombstones.get("session_000000000000", owner('a')))
        assertNull(tombstones.get("session_000000000099", owner('b')))
        assertEquals(
            BrowserWebRtcSessionState.FAILED,
            tombstones.get("session_000000000099", owner('a'))?.state,
        )
    }

    @Test
    fun `touch presence envelope exposes only public bounded fields`() {
        val json = BrowserTouchPresenceEvent(
            sourceDeviceId = "browser_0000000000000001",
            colorSlot = 2,
            phase = "move",
            x = 0.25,
            y = 0.75,
            sequence = 9_007_199_254_740_991L,
        ).toJsonString()

        assertEquals(
            "{\"type\":\"session_event\",\"event\":\"touch_presence\"," +
                "\"sourceDeviceId\":\"browser_0000000000000001\",\"colorSlot\":2," +
                "\"phase\":\"move\",\"x\":0.25,\"y\":0.75," +
                "\"sequence\":9007199254740991}",
            json,
        )
        assertFalse(json.contains("ownerKey"))
        assertFalse(json.contains("browserCredential"))
        assertFalse(json.contains("sourceSessionId"))
    }

    @Test
    fun `display name limit counts Unicode code points rather than UTF16 units`() {
        val allowed = "\uD83D\uDE97".repeat(64)
        BrowserWebRtcOffer(
            preferredCodec = BrowserWebRtcCodec.AUTO,
            sdp = "v=0",
            ownerKey = owner('a'),
            displayName = allowed,
        )

        assertThrows(IllegalArgumentException::class.java) {
            BrowserWebRtcOffer(
                preferredCodec = BrowserWebRtcCodec.AUTO,
                sdp = "v=0",
                ownerKey = owner('a'),
                displayName = allowed + "A",
            )
        }
    }

    @Test
    fun `live rename changes only matching public display name`() {
        val metadata = BrowserWebRtcSessionPublicMetadata(
            sessionId = "session_000000001",
            deviceId = "browser_0000000000000001",
            accessMode = BrowserSessionAccessMode.READ_ONLY,
            colorSlot = 2,
            displayName = "Old name",
            isMain = true,
            connectedSequence = 41L,
        )
        val before = BrowserWebRtcSessionSnapshot(
            sessionId = metadata.sessionId,
            state = BrowserWebRtcSessionState.READY,
            selectedCodec = BrowserWebRtcCodec.H264,
            answerSdp = "v=0\r\n",
            detail = "connected",
            publicMetadata = metadata,
        )

        val renamed = before.withDeviceDisplayName(metadata.deviceId, "Family tablet")

        assertEquals(
            before.copy(publicMetadata = metadata.copy(displayName = "Family tablet")),
            renamed,
        )
        assertEquals(before.sessionId, renamed.sessionId)
        assertEquals(before.state, renamed.state)
        assertEquals(before.selectedCodec, renamed.selectedCodec)
        assertEquals(before.answerSdp, renamed.answerSdp)
        assertEquals(before.detail, renamed.detail)
        assertEquals(metadata.deviceId, renamed.publicMetadata?.deviceId)
        assertEquals(metadata.accessMode, renamed.publicMetadata?.accessMode)
        assertEquals(metadata.colorSlot, renamed.publicMetadata?.colorSlot)
        assertEquals(metadata.isMain, renamed.publicMetadata?.isMain)
        assertEquals(metadata.connectedSequence, renamed.publicMetadata?.connectedSequence)
        assertSame(
            before,
            before.withDeviceDisplayName("browser_0000000000000002", "Ignored"),
        )
        assertSame(before, before.withDeviceDisplayName(metadata.deviceId, metadata.displayName))
    }

    @Test
    fun `live rename rejects names that could not have been persisted`() {
        val before = BrowserWebRtcSessionSnapshot(
            sessionId = "session_000000001",
            state = BrowserWebRtcSessionState.PENDING,
            publicMetadata = metadata(
                sessionId = "session_000000001",
                deviceId = "browser_0000000000000001",
                connectedSequence = 1L,
                colorSlot = 0,
            ),
        )

        listOf(
            "",
            "   ",
            "bad\u0000name",
            "line\nbreak",
            "\uD83D\uDCF1".repeat(65),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                before.withDeviceDisplayName("browser_0000000000000001", invalid)
            }
        }
    }

    @Test
    fun `authenticated status reconciliation forwards live limit and preferred main once`() {
        val calls = mutableListOf<Pair<Int, String?>>()

        val closed = reconcileBrowserSessionPolicy(
            sessionLimitProvider = { MAX_FREE_BROWSER_SESSIONS },
            preferredMainDeviceId = { "browser_0000000000000002" },
            reconcile = { maximum, preferred ->
                calls += maximum to preferred
                2
            },
        )

        assertEquals(2, closed)
        assertEquals(
            listOf(MAX_FREE_BROWSER_SESSIONS to "browser_0000000000000002"),
            calls,
        )
    }

    @Test
    fun `status reconciliation fails closed and contains callback failure`() {
        var observedLimit = 0
        val closed = reconcileBrowserSessionPolicy(
            sessionLimitProvider = { error("billing unavailable") },
            preferredMainDeviceId = { null },
            reconcile = { maximum, _ ->
                observedLimit = maximum
                error("controller stopped")
            },
        )

        assertEquals(MAX_FREE_BROWSER_SESSIONS, observedLimit)
        assertEquals(0, closed)
    }

    @Test
    fun `one owner holds the native pointer until terminal phase`() {
        var now = 1L
        val gate = BrowserTouchGestureGate(nowMillis = { now }, leaseMillis = 1_000L)

        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.DOWN)),
        )
        assertEquals(
            BrowserTouchGestureAdmission.OUT_OF_SEQUENCE,
            gate.tryAdmit(source('a'), touch(TouchPhase.DOWN)),
        )
        assertEquals(
            BrowserTouchGestureAdmission.BUSY,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)),
        )
        assertEquals(
            BrowserTouchGestureAdmission.OUT_OF_SEQUENCE,
            gate.tryAdmit(source('b'), touch(TouchPhase.MOVE)),
        )
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.MOVE)),
        )
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.UP)),
        )
        gate.complete(owner('a'), TouchPhase.UP, accepted = true)
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)),
        )

        now += 1_001L
        val cancelledTouches = mutableListOf<NormalizedTouch>()
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.DOWN)) { expiredSource, expiredTouch ->
                assertEquals(source('b'), expiredSource)
                cancelledTouches += expiredTouch.copy(phase = TouchPhase.CANCEL)
                true
            },
        )
        assertEquals(listOf(TouchPhase.CANCEL), cancelledTouches.map(NormalizedTouch::phase))
        assertEquals(
            BrowserTouchGestureAdmission.OUT_OF_SEQUENCE,
            gate.tryAdmit(source('b'), touch(TouchPhase.UP)),
        )
    }

    @Test
    fun `expired pointer requires accepted native cancel before takeover`() {
        var now = 1L
        val gate = BrowserTouchGestureGate(nowMillis = { now }, leaseMillis = 1_000L)
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.DOWN, x = 0.2, y = 0.3)),
        )
        now += 1_001L

        assertEquals(
            BrowserTouchGestureAdmission.RECOVERY_REJECTED,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)) { _, _ -> false },
        )
        var staleTouch: NormalizedTouch? = null
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)) { expiredSource, touch ->
                assertEquals("browser_a", expiredSource.deviceId)
                assertEquals(0, expiredSource.colorSlot)
                staleTouch = touch
                true
            },
        )
        assertEquals(0.2, staleTouch?.x)
        assertEquals(0.3, staleTouch?.y)
    }

    @Test
    fun `rejected down releases the pointer lease`() {
        val gate = BrowserTouchGestureGate(nowMillis = { 1L })
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.DOWN)),
        )

        gate.complete(owner('a'), TouchPhase.DOWN, accepted = false)

        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)),
        )
    }

    @Test
    fun `rejected terminal input retains ownership until an accepted recovery cancel`() {
        val gate = BrowserTouchGestureGate(nowMillis = { 1L })
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.DOWN)),
        )
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.UP)),
        )
        gate.complete(owner('a'), TouchPhase.UP, accepted = false)
        assertEquals(
            BrowserTouchGestureAdmission.BUSY,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)),
        )

        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.CANCEL)),
        )
        gate.complete(owner('a'), TouchPhase.CANCEL, accepted = false)
        assertEquals(
            BrowserTouchGestureAdmission.BUSY,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)),
        )
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('a'), touch(TouchPhase.CANCEL)),
        )
        gate.complete(owner('a'), TouchPhase.CANCEL, accepted = true)
        assertEquals(
            BrowserTouchGestureAdmission.ACCEPTED,
            gate.tryAdmit(source('b'), touch(TouchPhase.DOWN)),
        )
    }

    @Test
    fun `fallback transports reserve capacity for three browser sessions`() {
        assertEquals(3, BrowserProbeServer.MAX_FRAME_CLIENTS)
        assertEquals(9, BrowserProbeServer.MAX_AUDIO_CLIENTS)
        assertTrue(
            BrowserProbeServer.MAX_CLIENTS >=
                BrowserProbeServer.MAX_FRAME_CLIENTS + BrowserProbeServer.MAX_AUDIO_CLIENTS + 3,
        )
        assertTrue(BrowserProbeServer.MAX_ACCEPT_BACKLOG >= BrowserProbeServer.MAX_CLIENTS)
    }

    private fun owner(character: Char): String = browserWebRtcOwnerKey(character.toString().repeat(43))

    private fun source(character: Char) = BrowserTouchGestureSource(
        ownerKey = owner(character),
        deviceId = "browser_$character",
        colorSlot = (character - 'a') % 3,
    )

    private fun touch(
        phase: TouchPhase,
        x: Double = 0.5,
        y: Double = 0.5,
    ) = NormalizedTouch(phase = phase, x = x, y = y)

    private fun metadata(
        sessionId: String,
        deviceId: String,
        connectedSequence: Long,
        colorSlot: Int,
        accessMode: BrowserSessionAccessMode = BrowserSessionAccessMode.CONTROL,
    ) = BrowserWebRtcSessionPublicMetadata(
        sessionId = sessionId,
        deviceId = deviceId,
        accessMode = accessMode,
        colorSlot = colorSlot,
        connectedSequence = connectedSequence,
    )
}
