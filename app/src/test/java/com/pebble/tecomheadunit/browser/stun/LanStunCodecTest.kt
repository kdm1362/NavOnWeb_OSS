package com.pebble.tecomheadunit.browser.stun

import java.net.Inet4Address
import java.net.InetAddress
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanStunCodecTest {
    private val transactionId = byteArrayOf(
        0x00, 0x01, 0x02, 0x03,
        0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b,
    )

    @Test
    fun decodesHeaderOnlyRfc5389BindingRequest() {
        assertArrayEquals(transactionId, LanStunCodec.decodeBindingRequest(bindingRequest()))
    }

    @Test
    fun encodesIpv4XorMappedAddress() {
        val response = LanStunCodec.encodeBindingSuccess(
            transactionId = transactionId,
            mappedAddress = InetAddress.getByName("10.0.0.220") as Inet4Address,
            mappedPort = 50_000,
        )

        assertEquals(32, response.size)
        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x00, 0x0c), response.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42), response.copyOfRange(4, 8))
        assertArrayEquals(transactionId, response.copyOfRange(8, 20))
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x20, 0x00, 0x08,
                0x00, 0x01, 0xe2.toByte(), 0x42,
                0x2b, 0x12, 0xa4.toByte(), 0x9e.toByte(),
            ),
            response.copyOfRange(20, 32),
        )
    }

    @Test
    fun rejectsMalformedWrongCookieAndOversizedDatagrams() {
        assertNull(LanStunCodec.decodeBindingRequest(ByteArray(19)))
        assertNull(LanStunCodec.decodeBindingRequest(ByteArray(577)))
        assertNull(LanStunCodec.decodeBindingRequest(bindingRequest().also { it[1] = 0x02 }))
        assertNull(LanStunCodec.decodeBindingRequest(bindingRequest().also { it[4] = 0x20 }))
        assertNull(LanStunCodec.decodeBindingRequest(bindingRequest().also { it[3] = 0x04 }))

        val truncatedAttribute = bindingRequest(
            byteArrayOf(0x80.toByte(), 0x22, 0x00, 0x04, 0x01, 0x02, 0x03),
        )
        assertNull(LanStunCodec.decodeBindingRequest(truncatedAttribute))
    }

    @Test
    fun rejectsUnknownRequiredAttributeButIgnoresBoundedOptionalAttribute() {
        val required = bindingRequest(
            byteArrayOf(0x00, 0x03, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00),
        )
        assertNull(LanStunCodec.decodeBindingRequest(required))

        val optional = bindingRequest(
            byteArrayOf(0x80.toByte(), 0x22, 0x00, 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x00),
        )
        assertArrayEquals(transactionId, LanStunCodec.decodeBindingRequest(optional))
    }

    @Test
    fun validatesFingerprintWhenPresent() {
        val request = bindingRequest(ByteArray(8).also {
            it[0] = 0x80.toByte()
            it[1] = 0x28
            it[3] = 0x04
        })
        val crc = CRC32().apply { update(request, 0, request.size - 8) }.value.toInt() xor 0x5354554e
        writeInt(request, request.size - 4, crc)

        assertArrayEquals(transactionId, LanStunCodec.decodeBindingRequest(request))
        request[request.lastIndex] = (request.last().toInt() xor 1).toByte()
        assertNull(LanStunCodec.decodeBindingRequest(request))
    }

    @Test
    fun allowsOnlyPrivateIpv4LanClients() {
        assertTrue(LanStunServer.isAllowedClientAddress(InetAddress.getByName("10.0.0.50")))
        assertTrue(LanStunServer.isAllowedClientAddress(InetAddress.getByName("10.0.0.2")))
        assertTrue(!LanStunServer.isAllowedClientAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(!LanStunServer.isAllowedClientAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(!LanStunServer.isAllowedClientAddress(InetAddress.getByName("::1")))
    }

    @Test
    fun globalResponseBucketLimitsBurstAndRefillsAtSixtyFourPerSecond() {
        var nowNanos = 0L
        val limiter = LanStunResponseRateLimiter(clockNanos = { nowNanos })

        repeat(96) { assertTrue(limiter.tryAcquire()) }
        assertTrue(!limiter.tryAcquire())

        nowNanos += 500_000_000L
        repeat(32) { assertTrue(limiter.tryAcquire()) }
        assertTrue(!limiter.tryAcquire())

        nowNanos += 10_000_000_000L
        repeat(96) { assertTrue(limiter.tryAcquire()) }
        assertTrue(!limiter.tryAcquire())
    }

    private fun bindingRequest(attributes: ByteArray = ByteArray(0)): ByteArray {
        val request = ByteArray(20 + attributes.size)
        request[1] = 0x01
        request[2] = (attributes.size ushr 8).toByte()
        request[3] = attributes.size.toByte()
        request[4] = 0x21
        request[5] = 0x12
        request[6] = 0xa4.toByte()
        request[7] = 0x42
        transactionId.copyInto(request, 8)
        attributes.copyInto(request, 20)
        return request
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
