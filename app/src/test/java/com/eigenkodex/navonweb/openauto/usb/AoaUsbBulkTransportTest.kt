package com.eigenkodex.navonweb.openauto.usb

import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AoaUsbBulkTransportTest {
    @Test
    fun openRequestRequiresSafetyConfirmationAndBoundedTimeouts() {
        val selected = AoaUsbDeviceSelection("device", 1, 0x18D1, 0x2D00)
        assertEquals(
            AoaUsbStatusCode.SAFETY_CONFIRMATION_REQUIRED,
            validateBulkTransportOpenRequest(
                AoaBulkTransportOpenRequest(selected, safetyGateConfirmed = false),
            )?.code,
        )
        assertEquals(
            AoaUsbStatusCode.BULK_TRANSPORT_CONFIGURATION_INVALID,
            validateBulkTransportOpenRequest(
                AoaBulkTransportOpenRequest(
                    selectedAccessoryDevice = selected,
                    safetyGateConfirmed = true,
                    readTimeoutMillis = 0,
                ),
            )?.code,
        )
        assertEquals(
            null,
            validateBulkTransportOpenRequest(
                AoaBulkTransportOpenRequest(selected, safetyGateConfirmed = true),
            ),
        )
    }

    @Test
    fun writeUsesBoundedUsbChunksAndConfiguredTimeout() = runBlocking {
        val io = FakeBulkIo()
        val transport = AoaUsbBulkTransport(io, readTimeoutMillis = 111, writeTimeoutMillis = 222)
        val source = ByteArray(AoaUsbBulkTransport.MAX_USB_TRANSFER_BYTES + 1) { it.toByte() }

        transport.write(source)

        assertEquals(
            listOf(AoaUsbBulkTransport.MAX_USB_TRANSFER_BYTES, 1),
            io.writes.map { it.data.size },
        )
        assertEquals(listOf(222, 222), io.writes.map { it.timeoutMillis })
        assertArrayEquals(source, io.writes.flatMap { it.data.asIterable() }.toByteArray())
    }

    @Test
    fun readExactlyAccumulatesShortSuccessfulUsbReads() = runBlocking {
        val io = FakeBulkIo(
            reads = ArrayDeque(
                listOf(
                    byteArrayOf(1, 2),
                    byteArrayOf(3),
                    byteArrayOf(4, 5),
                ),
            ),
        )
        val transport = AoaUsbBulkTransport(io, readTimeoutMillis = 333, writeTimeoutMillis = 444)

        val result = transport.readExactly(5)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result)
        assertEquals(listOf(333, 333, 333), io.readTimeouts)
    }

    @Test
    fun failedReadAndShortWriteExposeStableErrorCodes() = runBlocking {
        val readIo = FakeBulkIo(readResults = ArrayDeque(listOf(-1)))
        val readTransport = AoaUsbBulkTransport(readIo, 100, 100)
        assertTransferError(AoaBulkTransferErrorCode.READ_TIMEOUT_STALL_OR_IO_FAILURE) {
            readTransport.readExactly(1)
        }

        val writeIo = FakeBulkIo(writeResultOverride = 1)
        val writeTransport = AoaUsbBulkTransport(writeIo, 100, 100)
        assertTransferError(AoaBulkTransferErrorCode.WRITE_INCOMPLETE) {
            writeTransport.write(byteArrayOf(1, 2))
        }
    }

    @Test
    fun closeIsIdempotentAndPreventsFurtherTransfers() = runBlocking {
        val io = FakeBulkIo()
        val transport = AoaUsbBulkTransport(io, 100, 100)

        transport.close()
        transport.close()

        assertEquals(1, io.closeCount)
        assertTransferError(AoaBulkTransferErrorCode.CLOSED) {
            transport.write(byteArrayOf(1))
        }
    }

    private suspend fun assertTransferError(
        expected: AoaBulkTransferErrorCode,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            fail("expected AoaBulkTransferException")
        } catch (error: AoaBulkTransferException) {
            assertEquals(expected, error.code)
        }
    }

    private data class WriteCall(val data: ByteArray, val timeoutMillis: Int)

    private class FakeBulkIo(
        private val reads: ArrayDeque<ByteArray> = ArrayDeque(),
        private val readResults: ArrayDeque<Int> = ArrayDeque(),
        private val writeResultOverride: Int? = null,
    ) : AoaBulkIo {
        val writes = mutableListOf<WriteCall>()
        val readTimeouts = mutableListOf<Int>()
        var closeCount = 0

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): Int {
            readTimeouts += timeoutMillis
            if (readResults.isNotEmpty()) return readResults.removeFirst()
            if (reads.isEmpty()) return -1
            val source = reads.removeFirst()
            require(source.size <= length)
            source.copyInto(buffer, destinationOffset = offset)
            return source.size
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeoutMillis: Int,
        ): Int {
            writes += WriteCall(
                data = buffer.copyOfRange(offset, offset + length),
                timeoutMillis = timeoutMillis,
            )
            return writeResultOverride ?: length
        }

        override fun close() {
            closeCount += 1
        }
    }
}
