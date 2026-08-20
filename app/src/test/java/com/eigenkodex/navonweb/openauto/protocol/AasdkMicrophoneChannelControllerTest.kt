package com.eigenkodex.navonweb.openauto.protocol

import com.eigenkodex.navonweb.openauto.OpenAutoMicrophoneFrame
import com.eigenkodex.navonweb.openauto.OpenAutoMicrophoneSource
import com.eigenkodex.navonweb.openauto.OpenAutoPcmFormat
import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkMicrophoneChannelControllerTest {
    @Test
    fun openResponseMustCompleteBeforePcmAndAckReleasesWindow() {
        val source = FakeMicrophoneSource().apply {
            available = true
            frames += frame(1L, 1)
            frames += frame(2L, 2)
        }
        val controller = AasdkMicrophoneChannelController(source)

        assertArrayEquals(bytes(0x00, 0x08, 0x08, 0x00), controller.onChannelOpen())
        assertArrayEquals(
            bytes(0x80, 0x03, 0x08, 0x02, 0x10, 0x01, 0x18, 0x00),
            controller.onSetupRequest(bytes(0x80, 0x00, 0x08, 0x01)),
        )
        val opened = controller.prepareOpenRequest(
            bytes(0x80, 0x05, 0x08, 0x01, 0x10, 0x01, 0x18, 0x01, 0x20, 0x01),
        )

        assertEquals(AasdkMicrophoneOpenStatus.STREAMING, opened.status)
        assertArrayEquals(bytes(0x80, 0x06, 0x08, 0x00, 0x10, 0x00), opened.response)
        assertTrue(opened.ancEnabled)
        assertTrue(opened.echoCancellationEnabled)
        assertEquals(1, opened.maxUnacked)
        assertNull(controller.nextTransmission(0L))

        controller.completeOpenResponse(opened)
        val first = controller.nextTransmission(0L)
        assertNotNull(first)
        assertTrue(controller.isCurrent(requireNotNull(first)))
        assertNull(controller.nextTransmission(0L))

        val ack = controller.onMediaAck(bytes(0x80, 0x04, 0x08, 0x00, 0x10, 0x01))
        assertEquals(1, ack.accepted)
        assertEquals(0, ack.outstanding)
        assertFalse(ack.stale)
        assertNotNull(controller.nextTransmission(0L))
    }

    @Test
    fun unavailableProducerFailsOnlyTheMicRequestAndCanRecover() {
        val source = FakeMicrophoneSource()
        val controller = AasdkMicrophoneChannelController(source)
        controller.onChannelOpen()
        controller.onSetupRequest(bytes(0x80, 0x00))

        val unavailable = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01, 0x20, 0x01))
        controller.completeOpenResponse(unavailable)
        assertEquals(AasdkMicrophoneOpenStatus.UNAVAILABLE, unavailable.status)
        assertArrayEquals(bytes(0x80, 0x06, 0x08, 0x00, 0x10, 0x01), unavailable.response)
        assertNull(controller.nextTransmission(0L))

        source.available = true
        source.frames += frame(3L, 3)
        val recovered = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        controller.completeOpenResponse(recovered)
        assertEquals(AasdkMicrophoneOpenStatus.STREAMING, recovered.status)
        assertNotNull(controller.nextTransmission(0L))
    }

    @Test
    fun closeInvalidatesLeasesAndLateAckWithoutClosingReusableSource() {
        val source = FakeMicrophoneSource().apply {
            available = true
            frames += frame(1L, 1)
        }
        val controller = AasdkMicrophoneChannelController(source)
        controller.onChannelOpen()
        controller.onSetupRequest(bytes(0x80, 0x00))
        val opened = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01, 0x20, 0x01))
        controller.completeOpenResponse(opened)
        val lease = requireNotNull(controller.nextTransmission(0L))

        val closed = controller.prepareOpenRequest(bytes(0x80, 0x05))
        controller.completeOpenResponse(closed)
        assertEquals(AasdkMicrophoneOpenStatus.CLOSED, closed.status)
        assertFalse(controller.isCurrent(lease))
        assertFalse(source.started)
        assertFalse(source.closed)

        val lateAck = controller.onMediaAck(bytes(0x80, 0x04, 0x08, 0x00, 0x10, 0x01))
        assertTrue(lateAck.stale)
        assertEquals(0, lateAck.accepted)
    }

    @Test
    fun channelOpenIsRequiredButAvSetupIsOptionalForInputOpen() {
        val controller = AasdkMicrophoneChannelController(FakeMicrophoneSource())
        assertThrows(AasdkProtocolException::class.java) {
            controller.onSetupRequest(bytes(0x80, 0x00))
        }
        assertThrows(AasdkProtocolException::class.java) {
            controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        }

        controller.onChannelOpen()
        val directOpen = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        assertEquals(AasdkMicrophoneOpenStatus.UNAVAILABLE, directOpen.status)
        controller.completeOpenResponse(directOpen)

        assertThrows(AasdkProtocolException::class.java) {
            controller.onSetupRequest(bytes(0x80, 0x00, 0x08, 0x02))
        }
        assertArrayEquals(
            bytes(0x80, 0x03, 0x08, 0x02, 0x10, 0x01, 0x18, 0x00),
            controller.onSetupRequest(bytes(0x80, 0x00, 0x08, 0x01)),
        )
    }

    @Test
    fun sourceStartStopAndPollFailuresStayInsideMicrophoneChannel() {
        val source = FakeMicrophoneSource().apply {
            available = true
            throwOnStart = true
        }
        val controller = AasdkMicrophoneChannelController(source)
        controller.onChannelOpen()
        controller.onSetupRequest(bytes(0x80, 0x00))

        val startFailed = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        assertEquals(AasdkMicrophoneOpenStatus.UNAVAILABLE, startFailed.status)
        controller.completeOpenResponse(startFailed)

        source.throwOnStart = false
        source.throwOnStop = true
        val stopFailed = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        assertEquals(AasdkMicrophoneOpenStatus.UNAVAILABLE, stopFailed.status)
        controller.completeOpenResponse(stopFailed)

        source.throwOnStop = false
        source.frames += frame(4L, 4)
        val opened = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        controller.completeOpenResponse(opened)
        assertEquals(AasdkMicrophoneOpenStatus.STREAMING, opened.status)
        source.throwOnPoll = true
        assertNull(controller.nextTransmission(0L))
        source.throwOnPoll = false
        assertNull(controller.nextTransmission(0L))

        source.throwOnStop = true
        controller.close()
    }

    @Test
    fun isStreamingReflectsLifecycleForPollCadenceSelection() {
        val source = FakeMicrophoneSource().apply {
            available = true
            frames += frame(1L, 1)
        }
        val controller = AasdkMicrophoneChannelController(source)
        assertFalse(controller.isStreaming())

        controller.onChannelOpen()
        controller.onSetupRequest(bytes(0x80, 0x00))
        assertFalse(controller.isStreaming())

        val opened = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        assertEquals(AasdkMicrophoneOpenStatus.STREAMING, opened.status)
        // The response has not reached the transport yet.
        assertFalse(controller.isStreaming())
        controller.completeOpenResponse(opened)
        assertTrue(controller.isStreaming())

        val stopped = controller.prepareOpenRequest(bytes(0x80, 0x05))
        controller.completeOpenResponse(stopped)
        assertEquals(AasdkMicrophoneOpenStatus.CLOSED, stopped.status)
        assertFalse(controller.isStreaming())

        val reopened = controller.prepareOpenRequest(bytes(0x80, 0x05, 0x08, 0x01))
        controller.completeOpenResponse(reopened)
        assertEquals(AasdkMicrophoneOpenStatus.STREAMING, reopened.status)
        assertTrue(controller.isStreaming())

        controller.close()
        assertFalse(controller.isStreaming())
    }

    @Test
    fun requestedSendWindowIsBoundedAndReportedAsEffectiveValue() {
        val source = FakeMicrophoneSource().apply {
            available = true
            repeat(9) { index -> frames += frame(index.toLong(), index) }
        }
        val controller = AasdkMicrophoneChannelController(source)
        controller.onChannelOpen()
        controller.onSetupRequest(bytes(0x80, 0x00))
        val opened = controller.prepareOpenRequest(
            bytes(0x80, 0x05, 0x08, 0x01, 0x20, 0x14),
        )
        controller.completeOpenResponse(opened)

        assertEquals(8, opened.maxUnacked)
        repeat(8) { assertNotNull(controller.nextTransmission(0L)) }
        assertNull(controller.nextTransmission(0L))
        val ack = controller.onMediaAck(bytes(0x80, 0x04, 0x08, 0x00, 0x10, 0x08))
        assertEquals(8, ack.accepted)
        assertNotNull(controller.nextTransmission(0L))
    }

    private class FakeMicrophoneSource : OpenAutoMicrophoneSource {
        override val format = OpenAutoPcmFormat(sampleRateHz = 16_000, channelCount = 1)
        val frames = ArrayDeque<OpenAutoMicrophoneFrame>()
        var available = false
        var started = false
        var closed = false
        var throwOnStart = false
        var throwOnStop = false
        var throwOnPoll = false

        override fun isAvailable(): Boolean = available && !closed

        override fun start(): Boolean {
            if (throwOnStart) throw IllegalStateException("start failed")
            if (!isAvailable()) return false
            started = true
            return true
        }

        override fun poll(timeoutMillis: Long): OpenAutoMicrophoneFrame? {
            if (throwOnPoll) throw IllegalStateException("poll failed")
            return if (started && !closed) frames.pollFirst() else null
        }

        override fun stop() {
            if (throwOnStop) throw IllegalStateException("stop failed")
            started = false
        }

        override fun close() {
            closed = true
            started = false
        }
    }

    private fun frame(timestamp: Long, sample: Int): OpenAutoMicrophoneFrame =
        OpenAutoMicrophoneFrame(timestamp, bytes(sample, 0))

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index ->
        values[index].toByte()
    }
}
