/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import java.io.EOFException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkProjectionSessionTest {
    @Test
    fun `receives encrypted application message without closing ownership`() = runBlocking {
        val payload = byteArrayOf(0x00, 0x05, 0x08, 0x01)
        val incoming = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 0,
            type = AasdkFrameType.BULK,
            controlMessage = false,
            ciphertextPayload = payload,
        )
        val transport = BufferTransport(incoming)
        val cipher = IdentityCipher()
        val session = AasdkProjectionSession(transport, cipher)

        val message = session.receiveMessage()

        assertEquals(0, message.channelId)
        assertFalse(message.controlMessage)
        assertTrue(message.encrypted)
        assertArrayEquals(payload, message.payload)
        assertTrue(session.isOpen)
        assertEquals(0, transport.closeCount)
        assertEquals(1, cipher.decodeCount)

        session.close()
        assertEquals(1, transport.closeCount)
        assertEquals(1, cipher.closeCount)
    }

    @Test
    fun `fragmented media survives interleaved bulk on another channel`() = runBlocking {
        val mediaFirstPayload = byteArrayOf(0x00, 0x01)
        val mediaLastPayload = byteArrayOf(0x55, 0x66)
        val focusPayload = byteArrayOf(0x00, 0x12, 0x08, 0x01)
        val first = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.FIRST,
            controlMessage = false,
            ciphertextPayload = mediaFirstPayload,
            declaredPlaintextTotalSize = mediaFirstPayload.size + mediaLastPayload.size,
        )
        val interleaved = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 0,
            type = AasdkFrameType.BULK,
            controlMessage = false,
            ciphertextPayload = focusPayload,
        )
        val last = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.LAST,
            controlMessage = false,
            ciphertextPayload = mediaLastPayload,
        )
        val session = AasdkProjectionSession(BufferTransport(first + interleaved + last), IdentityCipher())

        val focus = session.receiveMessage()
        val media = session.receiveMessage()

        assertEquals(0, focus.channelId)
        assertArrayEquals(focusPayload, focus.payload)
        assertEquals(3, media.channelId)
        assertArrayEquals(mediaFirstPayload + mediaLastPayload, media.payload)
        assertTrue(media.encrypted)
        assertTrue(session.isOpen)
        session.close()
    }

    @Test
    fun `fragmented specific message survives same channel control bulk`() = runBlocking {
        val firstPayload = byteArrayOf(0x00)
        val lastPayload = byteArrayOf(0x01, 0x02)
        val controlPayload = byteArrayOf(0x00, 0x07)
        val first = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.FIRST,
            controlMessage = false,
            ciphertextPayload = firstPayload,
            declaredPlaintextTotalSize = firstPayload.size + lastPayload.size,
        )
        val control = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.BULK,
            controlMessage = true,
            ciphertextPayload = controlPayload,
        )
        val last = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.LAST,
            controlMessage = false,
            ciphertextPayload = lastPayload,
        )
        val session = AasdkProjectionSession(BufferTransport(first + control + last), IdentityCipher())

        val controlMessage = session.receiveMessage()
        val specificMessage = session.receiveMessage()

        assertTrue(controlMessage.controlMessage)
        assertArrayEquals(controlPayload, controlMessage.payload)
        assertFalse(specificMessage.controlMessage)
        assertArrayEquals(firstPayload + lastPayload, specificMessage.payload)
        assertTrue(session.isOpen)
        session.close()
    }

    @Test
    fun `bulk on active logical stream still fails closed`() {
        val first = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.FIRST,
            controlMessage = false,
            ciphertextPayload = byteArrayOf(0x00),
            declaredPlaintextTotalSize = 3,
        )
        val bulk = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.BULK,
            controlMessage = false,
            ciphertextPayload = byteArrayOf(0x01, 0x02),
        )
        val transport = BufferTransport(first + bulk)
        val session = AasdkProjectionSession(transport, IdentityCipher())

        assertThrows(AasdkProtocolException::class.java) {
            runBlocking { session.receiveMessage() }
        }
        assertFalse(session.isOpen)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `plain control-specific ping can be received and sent explicitly`() = runBlocking {
        val request = byteArrayOf(0x00, 0x0B, 0x08, 0x2A)
        val response = byteArrayOf(0x00, 0x0C, 0x08, 0x2A)
        val incoming = AasdkFrameCodec.encodePlainMessage(
            channelId = AasdkFrameCodec.CONTROL_CHANNEL,
            controlMessage = false,
            payload = request,
        ).single()
        val transport = BufferTransport(incoming)
        val cipher = IdentityCipher()
        val session = AasdkProjectionSession(transport, cipher)

        val message = session.receiveMessage()
        session.sendMessage(
            channelId = AasdkFrameCodec.CONTROL_CHANNEL,
            controlMessage = false,
            payload = response,
            encrypted = false,
        )

        assertFalse(message.encrypted)
        assertArrayEquals(request, message.payload)
        assertEquals(0, cipher.decodeCount)
        assertEquals(0, cipher.encodeCount)
        assertEquals(1, transport.writes.size)
        assertArrayEquals(
            AasdkFrameCodec.encodePlainMessage(0, false, response).single(),
            transport.writes.single(),
        )
        session.close()
    }

    @Test
    fun `plain service-channel frame fails closed`() {
        val incoming = AasdkFrameCodec.encodePlainMessage(
            channelId = 3,
            controlMessage = false,
            payload = byteArrayOf(0x00, 0x01),
        ).single()
        val transport = BufferTransport(incoming)
        val cipher = IdentityCipher()
        val session = AasdkProjectionSession(transport, cipher)

        assertThrows(AasdkProtocolException::class.java) {
            runBlocking { session.receiveMessage() }
        }
        assertFalse(session.isOpen)
        assertEquals(1, transport.closeCount)
        assertEquals(1, cipher.closeCount)
    }

    @Test
    fun `fragment protection cannot change inside one message`() {
        val first = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 0,
            type = AasdkFrameType.FIRST,
            controlMessage = false,
            ciphertextPayload = byteArrayOf(0x00),
            declaredPlaintextTotalSize = 3,
        )
        val last = AasdkFrameCodec.encodePlainMessage(
            channelId = 0,
            controlMessage = false,
            payload = byteArrayOf(0x05, 0x00),
        ).single().let { bulk ->
            // Make the otherwise valid plain frame a LAST continuation.
            bulk.copyOf().also { it[1] = AasdkFrameType.LAST.bits.toByte() }
        }
        val transport = BufferTransport(first + last)
        val cipher = IdentityCipher()
        val session = AasdkProjectionSession(transport, cipher)

        assertThrows(AasdkProtocolException::class.java) {
            runBlocking { session.receiveMessage() }
        }
        assertFalse(session.isOpen)
    }

    @Test
    fun `message frame count is bounded and fails closed`() {
        val first = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 0,
            type = AasdkFrameType.FIRST,
            controlMessage = false,
            ciphertextPayload = byteArrayOf(0x00),
            declaredPlaintextTotalSize = AasdkProjectionSession.MAX_FRAMES_PER_MESSAGE + 2,
        )
        val middle = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 0,
            type = AasdkFrameType.MIDDLE,
            controlMessage = false,
            ciphertextPayload = ByteArray(0),
        )
        var incoming = first
        repeat(AasdkProjectionSession.MAX_FRAMES_PER_MESSAGE - 1) {
            incoming += middle
        }
        val transport = BufferTransport(incoming)
        val cipher = IdentityCipher()
        val session = AasdkProjectionSession(transport, cipher)

        assertThrows(AasdkProtocolException::class.java) {
            runBlocking { session.receiveMessage() }
        }
        assertFalse(session.isOpen)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `concurrent sends use only one transport writer`() = runBlocking {
        val transport = GatedWriteTransport()
        val session = AasdkProjectionSession(transport, IdentityCipher())

        val first = async { session.sendMessage(1, true, byteArrayOf(0x00, 0x07, 0x01)) }
        transport.firstWriteStarted.await()
        val second = async { session.sendMessage(2, true, byteArrayOf(0x00, 0x07, 0x02)) }
        yield()

        assertEquals(1, transport.enteredWrites.get())
        transport.allowWrites.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, transport.maximumConcurrentWrites.get())
        assertEquals(2, transport.writes.size)
        session.close()
    }

    @Test
    fun `cancelling a blocked receive closes transport and cipher once`() = runBlocking {
        val transport = HangingReadTransport()
        val cipher = IdentityCipher()
        val session = AasdkProjectionSession(transport, cipher)
        val reader = launch { session.receiveMessage() }
        transport.readStarted.await()

        reader.cancelAndJoin()
        session.close()

        assertFalse(session.isOpen)
        assertEquals(1, transport.closeCount)
        assertEquals(1, cipher.closeCount)
    }

    private class IdentityCipher : AasdkSessionCipher {
        var encodeCount = 0
        var decodeCount = 0
        var closeCount = 0

        override fun encode(
            channelId: Int,
            controlMessage: Boolean,
            plaintext: ByteArray,
        ): List<ByteArray> {
            encodeCount += 1
            return listOf(
                AasdkFrameCodec.encodeEncryptedFrame(
                    channelId = channelId,
                    type = AasdkFrameType.BULK,
                    controlMessage = controlMessage,
                    ciphertextPayload = plaintext.copyOf(),
                ),
            )
        }

        override fun decode(frame: AasdkFrame): AasdkFrame {
            decodeCount += 1
            return frame.copy(encrypted = false, payload = frame.payload.copyOf())
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class BufferTransport(
        private val incoming: ByteArray,
    ) : AasdkByteTransport {
        val writes = mutableListOf<ByteArray>()
        var closeCount = 0
        private var offset = 0

        override suspend fun write(data: ByteArray) {
            writes += data.copyOf()
        }

        override suspend fun readExactly(size: Int): ByteArray {
            if (incoming.size - offset < size) throw EOFException("fixture exhausted")
            return incoming.copyOfRange(offset, offset + size).also { offset += size }
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class GatedWriteTransport : AasdkByteTransport {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val allowWrites = CompletableDeferred<Unit>()
        val enteredWrites = AtomicInteger(0)
        val maximumConcurrentWrites = AtomicInteger(0)
        val writes = mutableListOf<ByteArray>()
        private val activeWrites = AtomicInteger(0)

        override suspend fun write(data: ByteArray) {
            enteredWrites.incrementAndGet()
            val active = activeWrites.incrementAndGet()
            maximumConcurrentWrites.updateAndGet { maxOf(it, active) }
            firstWriteStarted.complete(Unit)
            allowWrites.await()
            writes += data.copyOf()
            activeWrites.decrementAndGet()
        }

        override suspend fun readExactly(size: Int): ByteArray =
            throw UnsupportedOperationException("not used")

        override fun close() = Unit
    }

    private class HangingReadTransport : AasdkByteTransport {
        val readStarted = CompletableDeferred<Unit>()
        var closeCount = 0

        override suspend fun write(data: ByteArray) = Unit

        override suspend fun readExactly(size: Int): ByteArray {
            readStarted.complete(Unit)
            awaitCancellation()
        }

        override fun close() {
            closeCount += 1
        }
    }
}
