/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import com.pebble.tecomheadunit.openauto.AasdkAudioSinkException
import com.pebble.tecomheadunit.openauto.OpenAutoAudioChannel
import com.pebble.tecomheadunit.openauto.OpenAutoAudioFocus
import com.pebble.tecomheadunit.openauto.OpenAutoPcmFormat
import com.pebble.tecomheadunit.openauto.OpenAutoPcmFrame
import com.pebble.tecomheadunit.openauto.OpenAutoPcmSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkAudioChannelControllerTest {
    @Test
    fun `pinned audio channels expose exact PCM formats`() {
        assertEquals(OpenAutoPcmFormat(48_000, 2), OpenAutoAudioChannel.MEDIA.format)
        assertEquals(OpenAutoPcmFormat(16_000, 1), OpenAutoAudioChannel.GUIDANCE.format)
        assertEquals(OpenAutoPcmFormat(16_000, 1), OpenAutoAudioChannel.SYSTEM.format)
        assertEquals(OpenAutoAudioChannel.MEDIA, OpenAutoAudioChannel.fromAasdkChannelId(4))
        assertEquals(OpenAutoAudioChannel.GUIDANCE, OpenAutoAudioChannel.fromAasdkChannelId(5))
        assertEquals(OpenAutoAudioChannel.SYSTEM, OpenAutoAudioChannel.fromAasdkChannelId(6))
    }

    @Test
    fun `timestamped media reaches sink as owned PCM before ack`() {
        val sink = RecordingSink()
        val controller = AasdkAudioChannelController(sink)
        openSetupStart(controller, channelId = 4, session = 7)
        val wirePcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val payload = bytes(
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x2C,
        ) + wirePcm

        val reply = controller.onMedia(4, payload)

        assertEquals(AasdkAudioWireStatus.PCM_ACCEPTED, reply.status)
        assertArrayEquals(bytes(0x80, 0x04, 0x08, 0x07, 0x10, 0x01), reply.payload)
        val frame = sink.frames.single()
        assertEquals(OpenAutoAudioChannel.MEDIA, frame.channel)
        assertEquals(300L, frame.rawTimestamp)
        assertArrayEquals(wirePcm, frame.bytes)
        assertTrue(frame.bytes !== payload)
        assertEquals(1, frame.sampleFrameCount)
    }

    @Test
    fun `untimestamped mono guidance packet is accepted`() {
        val sink = RecordingSink()
        val controller = AasdkAudioChannelController(sink)
        openSetupStart(controller, channelId = 5, session = 9)

        val reply = controller.onMedia(5, bytes(0x00, 0x01, 0x34, 0x12))

        assertEquals(null, sink.frames.single().rawTimestamp)
        assertArrayEquals(bytes(0x34, 0x12), sink.frames.single().bytes)
        assertArrayEquals(bytes(0x80, 0x04, 0x08, 0x09, 0x10, 0x01), reply.payload)
    }

    @Test
    fun `AAC or other unadvertised setup selector is rejected`() {
        val sink = RecordingSink()
        val controller = AasdkAudioChannelController(sink)
        assertEquals(AasdkAudioWireStatus.CHANNEL_OPENED, controller.onChannelOpen(4).status)

        val reply = controller.onSetupRequest(4, bytes(0x80, 0x00, 0x08, 0x02))

        assertEquals(AasdkAudioWireStatus.SETUP_REJECTED_UNSUPPORTED_CODEC, reply.status)
        assertArrayEquals(bytes(0x80, 0x03, 0x08, 0x01, 0x10, 0x01, 0x18, 0x00), reply.payload)
        assertThrows(AasdkProtocolException::class.java) {
            controller.onStartIndication(4, start(session = 1))
        }
    }

    @Test
    fun `legacy index zero and modern PCM selector one are both bounded to PCM`() {
        for (selector in listOf(0, 1)) {
            val sink = RecordingSink()
            val controller = AasdkAudioChannelController(sink)
            controller.onChannelOpen(6)

            val reply = controller.onSetupRequest(6, bytes(0x80, 0x00, 0x08, selector))

            assertEquals(AasdkAudioWireStatus.SETUP_READY, reply.status)
            assertArrayEquals(bytes(0x80, 0x03, 0x08, 0x02, 0x10, 0x01, 0x18, 0x00), reply.payload)
            controller.onStartIndication(6, start(session = 3, config = selector))
            controller.close()
        }
    }

    @Test
    fun `media ack is withheld when synchronous sink rejects PCM`() {
        val sink = RecordingSink(acceptFrames = false)
        val controller = AasdkAudioChannelController(sink)
        openSetupStart(controller, channelId = 4, session = 7)

        assertThrows(AasdkAudioSinkException::class.java) {
            controller.onMedia(4, bytes(0x00, 0x01, 0x01, 0x02, 0x03, 0x04))
        }
        assertEquals(1, sink.frames.size)
    }

    @Test
    fun `PCM packets must be sample frame aligned`() {
        val sink = RecordingSink()
        val controller = AasdkAudioChannelController(sink)
        openSetupStart(controller, channelId = 4, session = 7)

        assertThrows(AasdkProtocolException::class.java) {
            controller.onMedia(4, bytes(0x00, 0x01, 0x01, 0x02))
        }
        assertTrue(sink.frames.isEmpty())
    }

    @Test
    fun `start stop and close drive callback lifecycle once`() {
        val sink = RecordingSink()
        val controller = AasdkAudioChannelController(sink)
        openSetupStart(controller, channelId = 6, session = 11)

        val stopped = controller.onStopIndication(6, bytes(0x80, 0x02))
        controller.close()
        controller.close()

        assertEquals(AasdkAudioWireStatus.STREAM_SUSPENDED, stopped.status)
        assertEquals(listOf(OpenAutoAudioChannel.SYSTEM), sink.suspended)
        assertEquals(listOf(OpenAutoAudioChannel.SYSTEM), sink.closed)
    }

    @Test
    fun `focus callback follows pinned gain and release response mapping`() {
        val sink = RecordingSink()
        val controller = AasdkAudioChannelController(sink)

        val navigation = controller.onAudioFocusRequest(bytes(0x00, 0x12, 0x08, 0x03))
        val release = controller.onAudioFocusRequest(bytes(0x00, 0x12, 0x08, 0x04))

        assertEquals(
            listOf(OpenAutoAudioFocus.GAIN_NAVIGATION, OpenAutoAudioFocus.RELEASE),
            sink.focus,
        )
        assertArrayEquals(bytes(0x00, 0x13, 0x08, 0x01), navigation.payload)
        assertArrayEquals(bytes(0x00, 0x13, 0x08, 0x03), release.payload)
    }

    @Test
    fun `open rejection returns required proto2 failure and leaves channel closed`() {
        val sink = RecordingSink(acceptOpen = false)
        val controller = AasdkAudioChannelController(sink)

        val reply = controller.onChannelOpen(4)

        assertEquals(AasdkAudioWireStatus.CHANNEL_OPEN_REJECTED, reply.status)
        assertArrayEquals(bytes(0x00, 0x08, 0x08, 0x01), reply.payload)
        assertThrows(AasdkProtocolException::class.java) {
            controller.onSetupRequest(4, bytes(0x80, 0x00))
        }
    }

    private fun openSetupStart(
        controller: AasdkAudioChannelController,
        channelId: Int,
        session: Int,
    ) {
        assertEquals(AasdkAudioWireStatus.CHANNEL_OPENED, controller.onChannelOpen(channelId).status)
        assertEquals(
            AasdkAudioWireStatus.SETUP_READY,
            controller.onSetupRequest(channelId, bytes(0x80, 0x00)).status,
        )
        assertEquals(
            AasdkAudioWireStatus.STREAM_STARTED,
            controller.onStartIndication(channelId, start(session)).status,
        )
    }

    private fun start(session: Int, config: Int = 0): ByteArray =
        bytes(0x80, 0x01, 0x08, session, 0x10, config)

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }

    private class RecordingSink(
        private val acceptOpen: Boolean = true,
        private val acceptFrames: Boolean = true,
    ) : OpenAutoPcmSink {
        val opened = mutableListOf<Pair<OpenAutoAudioChannel, OpenAutoPcmFormat>>()
        val started = mutableListOf<OpenAutoAudioChannel>()
        val frames = mutableListOf<OpenAutoPcmFrame>()
        val suspended = mutableListOf<OpenAutoAudioChannel>()
        val closed = mutableListOf<OpenAutoAudioChannel>()
        val focus = mutableListOf<OpenAutoAudioFocus>()

        override fun onOpen(channel: OpenAutoAudioChannel, format: OpenAutoPcmFormat): Boolean {
            opened += channel to format
            return acceptOpen
        }

        override fun onStart(channel: OpenAutoAudioChannel): Boolean {
            started += channel
            return true
        }

        override fun onPcmFrame(frame: OpenAutoPcmFrame): Boolean {
            frames += frame
            return acceptFrames
        }

        override fun onSuspend(channel: OpenAutoAudioChannel) {
            suspended += channel
        }

        override fun onClose(channel: OpenAutoAudioChannel) {
            closed += channel
        }

        override fun onAudioFocusChanged(focus: OpenAutoAudioFocus) {
            this.focus += focus
        }
    }
}
