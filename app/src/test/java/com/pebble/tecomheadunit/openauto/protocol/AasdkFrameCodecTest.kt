/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AasdkFrameCodecTest {
    @Test
    fun `version request matches pinned aasdk wire layout`() {
        assertArrayEquals(
            byteArrayOf(
                0x00,
                0x03,
                0x00,
                0x06,
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x01,
            ),
            AasdkBootstrapCodec.versionRequest(),
        )
    }

    @Test
    fun `version response fixture is parsed`() {
        val response = AasdkBootstrapCodec.parseVersionResponse(
            byteArrayOf(
                0x00,
                0x03,
                0x00,
                0x08,
                0x00,
                0x02,
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
            ),
        )

        assertEquals(1, response.major)
        assertEquals(1, response.minor)
        assertTrue(response.matches)
    }

    @Test
    fun `TLS handshake flight matches pinned control wire layout`() {
        val encoded = AasdkBootstrapCodec.tlsHandshake(byteArrayOf(0x16, 0x03, 0x03)).single()

        assertArrayEquals(
            byteArrayOf(
                0x00,
                0x03,
                0x00,
                0x05,
                0x00,
                0x03,
                0x16,
                0x03,
                0x03,
            ),
            encoded,
        )
        val decoded = AasdkFrameCodec.decodeFrame(encoded)
        assertArrayEquals(
            byteArrayOf(0x16, 0x03, 0x03),
            AasdkBootstrapCodec.parseTlsHandshake(
                AasdkPlainMessage(decoded.channelId, decoded.controlMessage, decoded.payload),
            ),
        )
    }

    @Test
    fun `AuthComplete success matches pinned protobuf wire layout`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x03, 0x00, 0x04, 0x00, 0x04, 0x08, 0x00),
            AasdkBootstrapCodec.authCompleteSuccess(),
        )
    }

    @Test
    fun `large plain message is split with extended first frame`() {
        val payload = ByteArray(AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD + 17) { (it and 0xFF).toByte() }
        val frames = AasdkFrameCodec.encodePlainMessage(
            channelId = 3,
            controlMessage = true,
            payload = payload,
        )

        assertEquals(2, frames.size)
        val first = AasdkFrameCodec.decodeFrame(frames[0])
        val last = AasdkFrameCodec.decodeFrame(frames[1])
        assertEquals(AasdkFrameType.FIRST, first.type)
        assertEquals(payload.size, first.declaredTotalSize)
        assertTrue(first.controlMessage)
        assertFalse(first.encrypted)
        assertEquals(AasdkFrameType.LAST, last.type)
        assertArrayEquals(payload, first.payload + last.payload)
    }

    @Test
    fun `trailing bytes are rejected`() {
        val valid = AasdkBootstrapCodec.versionRequest()
        assertThrows(AasdkProtocolException::class.java) {
            AasdkFrameCodec.decodeFrame(valid + 0x00)
        }
    }

    @Test
    fun `unknown high flag bits are ignored like pinned upstream`() {
        val extended = AasdkBootstrapCodec.versionRequest().copyOf()
        extended[1] = (extended[1].toInt() or 0x10).toByte()

        val decoded = AasdkFrameCodec.decodeFrame(extended)

        assertEquals(AasdkFrameType.BULK, decoded.type)
        assertFalse(decoded.encrypted)
    }

    @Test
    fun `exact plaintext fragment limit uses one bulk frame`() {
        val payload = ByteArray(AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD)

        val frames = AasdkFrameCodec.encodePlainMessage(3, false, payload)

        assertEquals(1, frames.size)
        assertEquals(AasdkFrameType.BULK, AasdkFrameCodec.decodeFrame(frames.single()).type)
    }

    @Test
    fun `encrypted wire payload may exceed plaintext fragment limit`() {
        val ciphertext = ByteArray(AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD + 29)
        val encoded = AasdkFrameCodec.encodeEncryptedFrame(
            channelId = 3,
            type = AasdkFrameType.FIRST,
            controlMessage = false,
            ciphertextPayload = ciphertext,
            declaredPlaintextTotalSize = AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD + 10,
        )

        val decoded = AasdkFrameCodec.decodeFrame(encoded)

        assertTrue(decoded.encrypted)
        assertEquals(ciphertext.size, decoded.payload.size)
        assertEquals(AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD + 10, decoded.declaredTotalSize)
    }

    @Test
    fun `plain fragmented message is reassembled and metadata is preserved`() {
        val payload = ByteArray(AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD * 2 + 17) {
            (it and 0xFF).toByte()
        }
        val assembler = AasdkPlainMessageAssembler()
        val messages = AasdkFrameCodec.encodePlainMessage(7, true, payload)
            .map(AasdkFrameCodec::decodeFrame)
            .mapNotNull(assembler::accept)

        assertEquals(1, messages.size)
        assertEquals(7, messages.single().channelId)
        assertTrue(messages.single().controlMessage)
        assertArrayEquals(payload, messages.single().payload)
    }

    @Test
    fun `assembler resets after fragment metadata mismatch`() {
        val payload = ByteArray(AasdkFrameCodec.MAX_PLAINTEXT_FRAME_PAYLOAD + 1)
        val frames = AasdkFrameCodec.encodePlainMessage(3, false, payload)
            .map(AasdkFrameCodec::decodeFrame)
        val assembler = AasdkPlainMessageAssembler()
        assembler.accept(frames.first())

        assertThrows(AasdkProtocolException::class.java) {
            assembler.accept(frames.last().copy(channelId = 4))
        }

        val bulk = AasdkFrameCodec.decodeFrame(
            AasdkFrameCodec.encodePlainMessage(4, false, byteArrayOf(1)).single(),
        )
        assertArrayEquals(byteArrayOf(1), assembler.accept(bulk)?.payload)
    }
}
