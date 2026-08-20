/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.audio

import com.eigenkodex.navonweb.audio.Pcm16Format
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAudioStreamHubTest {
    @Test
    fun publishesAlignedPcmToTheSelectedTrackOnly() {
        val hub = hub()
        val media = checkNotNull(hub.subscribe(BrowserAudioTrack.MEDIA))
        val speech = checkNotNull(hub.subscribe(BrowserAudioTrack.SPEECH))
        val packet = byteArrayOf(1, 0, 2, 0)

        assertTrue(hub.publish(BrowserAudioTrack.MEDIA, packet))

        assertArrayEquals(packet, media.poll(0))
        assertNull(speech.poll(0))
        assertEquals(1L, hub.snapshot().getValue(BrowserAudioTrack.MEDIA).packetsPublished)
    }

    @Test
    fun rejectsEmptyOrPartialPcmFrames() {
        val hub = hub()

        assertFalse(hub.publish(BrowserAudioTrack.MEDIA, ByteArray(0)))
        assertFalse(hub.publish(BrowserAudioTrack.MEDIA, byteArrayOf(1, 2)))
        assertFalse(hub.publish(BrowserAudioTrack.SPEECH, byteArrayOf(1)))
        assertEquals(0L, hub.snapshot().getValue(BrowserAudioTrack.MEDIA).packetsPublished)
    }

    @Test
    fun slowSubscriberDropsOldestWithoutBlockingPublisher() {
        val hub = hub()
        val subscription = checkNotNull(hub.subscribe(BrowserAudioTrack.SPEECH))
        repeat(BrowserAudioSubscription.MAX_QUEUED_CHUNKS + 3) { index ->
            assertTrue(hub.publish(BrowserAudioTrack.SPEECH, byteArrayOf(index.toByte(), 0)))
        }

        assertArrayEquals(byteArrayOf(3, 0), subscription.poll(0))
        assertEquals(3L, hub.snapshot().getValue(BrowserAudioTrack.SPEECH).packetsDropped)
    }

    @Test
    fun threePeerSubscriptionsReceiveTheSameAudioFrameIndependently() {
        val hub = hub()
        val peers = List(3) { checkNotNull(hub.subscribe(BrowserAudioTrack.MEDIA)) }
        val packet = byteArrayOf(1, 0, 2, 0)

        assertTrue(hub.publish(BrowserAudioTrack.MEDIA, packet))

        peers.forEach { peer -> assertArrayEquals(packet, peer.poll(0)) }
        peers[0].close()
        assertTrue(hub.publish(BrowserAudioTrack.MEDIA, byteArrayOf(3, 0, 4, 0)))
        assertNull(peers[0].poll(0))
        assertArrayEquals(byteArrayOf(3, 0, 4, 0), peers[1].poll(0))
        assertArrayEquals(byteArrayOf(3, 0, 4, 0), peers[2].poll(0))
    }

    @Test
    fun resetClosesCurrentStreamsButAllowsFreshSubscriptions() {
        val hub = hub()
        val old = checkNotNull(hub.subscribe(BrowserAudioTrack.SYSTEM))

        hub.reset()

        assertTrue(old.isClosed)
        assertNull(old.poll(0))
        val fresh = checkNotNull(hub.subscribe(BrowserAudioTrack.SYSTEM))
        assertTrue(hub.publish(BrowserAudioTrack.SYSTEM, byteArrayOf(7, 0)))
        assertArrayEquals(byteArrayOf(7, 0), fresh.poll(0))
    }

    @Test
    fun closeRejectsPublishAndSubscriptions() {
        val hub = hub()
        val subscription = checkNotNull(hub.subscribe(BrowserAudioTrack.MEDIA))

        hub.close()

        assertTrue(subscription.isClosed)
        assertFalse(hub.publish(BrowserAudioTrack.MEDIA, byteArrayOf(1, 0, 2, 0)))
        assertNull(hub.subscribe(BrowserAudioTrack.MEDIA))
    }

    @Test
    fun wireNamesAreClosed() {
        assertEquals(BrowserAudioTrack.MEDIA, BrowserAudioTrack.fromWireName("media"))
        assertEquals(BrowserAudioTrack.SPEECH, BrowserAudioTrack.fromWireName(" SPEECH "))
        assertNull(BrowserAudioTrack.fromWireName("microphone"))
    }

    private fun hub() = BrowserAudioStreamHub(
        mapOf(
            BrowserAudioTrack.MEDIA to Pcm16Format(48_000, 2),
            BrowserAudioTrack.SPEECH to Pcm16Format(16_000, 1),
            BrowserAudioTrack.SYSTEM to Pcm16Format(16_000, 1),
        ),
    )
}
