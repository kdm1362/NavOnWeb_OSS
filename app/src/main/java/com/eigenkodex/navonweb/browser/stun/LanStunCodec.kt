/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.stun

import java.net.Inet4Address
import java.util.zip.CRC32

/** Minimal RFC 5389 codec for an unauthenticated LAN Binding discovery exchange. */
internal object LanStunCodec {
    const val MAX_DATAGRAM_BYTES = 576
    const val TRANSACTION_ID_BYTES = 12

    private const val HEADER_BYTES = 20
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS_RESPONSE = 0x0101
    private const val MAGIC_COOKIE = 0x2112A442
    private const val XOR_MAPPED_ADDRESS = 0x0020
    private const val PADDING = 0x0026
    private const val FINGERPRINT = 0x8028
    private const val FINGERPRINT_XOR = 0x5354554e

    /**
     * Returns the transaction ID only for a complete Binding Request that is safe to answer.
     *
     * This tiny responder does not implement authentication, alternate addresses, response-port,
     * or ICE connectivity checks. Therefore PADDING is the only comprehension-required request
     * attribute it can safely ignore. Unknown comprehension-optional attributes are ignored as
     * RFC 5389 permits, while a present FINGERPRINT is verified.
     */
    fun decodeBindingRequest(datagram: ByteArray, length: Int = datagram.size): ByteArray? {
        if (length !in HEADER_BYTES..MAX_DATAGRAM_BYTES || length > datagram.size) return null
        if (readUnsignedShort(datagram, 0) != BINDING_REQUEST) return null
        val messageLength = readUnsignedShort(datagram, 2)
        if (messageLength % 4 != 0 || HEADER_BYTES + messageLength != length) return null
        if (readInt(datagram, 4) != MAGIC_COOKIE) return null

        var offset = HEADER_BYTES
        while (offset < length) {
            if (length - offset < 4) return null
            val type = readUnsignedShort(datagram, offset)
            val valueLength = readUnsignedShort(datagram, offset + 2)
            val valueOffset = offset + 4
            val paddedLength = (valueLength + 3) and -4
            if (paddedLength < valueLength || valueOffset > length - paddedLength) return null

            if (type < 0x8000 && type != PADDING) return null
            if (type == FINGERPRINT) {
                if (valueLength != 4 || valueOffset + paddedLength != length) return null
                val expected = crc32(datagram, offset) xor FINGERPRINT_XOR
                if (readInt(datagram, valueOffset) != expected) return null
            }
            offset = valueOffset + paddedLength
        }
        if (offset != length) return null
        return datagram.copyOfRange(8, 8 + TRANSACTION_ID_BYTES)
    }

    fun encodeBindingSuccess(
        transactionId: ByteArray,
        mappedAddress: Inet4Address,
        mappedPort: Int,
    ): ByteArray {
        require(transactionId.size == TRANSACTION_ID_BYTES) { "invalid STUN transaction ID" }
        require(mappedPort in 1..65_535) { "invalid mapped port" }

        val response = ByteArray(32)
        writeUnsignedShort(response, 0, BINDING_SUCCESS_RESPONSE)
        writeUnsignedShort(response, 2, 12)
        writeInt(response, 4, MAGIC_COOKIE)
        transactionId.copyInto(response, destinationOffset = 8)
        writeUnsignedShort(response, 20, XOR_MAPPED_ADDRESS)
        writeUnsignedShort(response, 22, 8)
        response[24] = 0
        response[25] = 0x01
        writeUnsignedShort(response, 26, mappedPort xor (MAGIC_COOKIE ushr 16))
        val address = mappedAddress.address
        for (index in address.indices) {
            response[28 + index] = (address[index].toInt() xor cookieByte(index)).toByte()
        }
        return response
    }

    private fun cookieByte(index: Int): Int =
        (MAGIC_COOKIE ushr (24 - (index * 8))) and 0xff

    private fun crc32(bytes: ByteArray, length: Int): Int = CRC32().run {
        update(bytes, 0, length)
        value.toInt()
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
