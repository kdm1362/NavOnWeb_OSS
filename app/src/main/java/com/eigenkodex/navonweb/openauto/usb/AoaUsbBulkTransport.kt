/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.eigenkodex.navonweb.openauto.protocol.AasdkByteTransport
import com.eigenkodex.navonweb.openauto.protocol.AasdkFrameCodec
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AoaBulkTransportOpenRequest(
    /** Must be the selection obtained after the phone re-enumerates as an AOA accessory. */
    val selectedAccessoryDevice: AoaUsbDeviceSelection,
    /** Must reflect the same parked/bench gate used for the AOA mode-switch request. */
    val safetyGateConfirmed: Boolean,
    val readTimeoutMillis: Int = DEFAULT_TRANSFER_TIMEOUT_MS,
    val writeTimeoutMillis: Int = DEFAULT_TRANSFER_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_TRANSFER_TIMEOUT_MS = 5_000
        const val MAX_TRANSFER_TIMEOUT_MS = 60_000
    }
}

internal sealed interface AoaBulkTransportOpenResult {
    data class Ready(
        val status: AoaUsbStatus,
        val transport: AoaUsbBulkTransport,
    ) : AoaBulkTransportOpenResult

    data class Rejected(val status: AoaUsbStatus) : AoaBulkTransportOpenResult
}

internal fun validateBulkTransportOpenRequest(
    request: AoaBulkTransportOpenRequest,
): AoaUsbStatus? = when {
    !request.safetyGateConfirmed ->
        AoaUsbStatus(AoaUsbStatusCode.SAFETY_CONFIRMATION_REQUIRED)
    request.readTimeoutMillis !in 1..AoaBulkTransportOpenRequest.MAX_TRANSFER_TIMEOUT_MS ||
        request.writeTimeoutMillis !in 1..AoaBulkTransportOpenRequest.MAX_TRANSFER_TIMEOUT_MS ->
        AoaUsbStatus(AoaUsbStatusCode.BULK_TRANSPORT_CONFIGURATION_INVALID)
    else -> null
}

internal enum class AoaBulkTransferErrorCode {
    CLOSED,
    INVALID_READ_SIZE,
    READ_TIMEOUT_STALL_OR_IO_FAILURE,
    READ_RESULT_INVALID,
    WRITE_TIMEOUT_STALL_OR_IO_FAILURE,
    WRITE_INCOMPLETE,
}

internal class AoaBulkTransferException(
    val code: AoaBulkTransferErrorCode,
    val requestedBytes: Int? = null,
    val transferredBytes: Int? = null,
    cause: Throwable? = null,
) : IOException(
    buildString {
        append("AOA USB bulk transfer failed: ").append(code.name)
        requestedBytes?.let { append(" requested=").append(it) }
        transferredBytes?.let { append(" transferred=").append(it) }
    },
    cause,
)

/** Small seam that keeps transfer and lifecycle behavior JVM-testable without Android USB mocks. */
internal interface AoaBulkIo {
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int
    fun close()
}

internal class AndroidAoaBulkIo(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AoaBulkIo {
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int = connection.bulkTransfer(bulkIn, buffer, offset, length, timeoutMillis)

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int = connection.bulkTransfer(bulkOut, buffer, offset, length, timeoutMillis)

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }
}

/**
 * Blocking Android USB operations are isolated on Dispatchers.IO. A read and a write may proceed
 * concurrently on their distinct endpoints, while each direction is serialized. Closing is
 * idempotent and waits for active bounded transfers before releasing the claimed interface.
 */
internal class AoaUsbBulkTransport(
    private val io: AoaBulkIo,
    private val readTimeoutMillis: Int,
    private val writeTimeoutMillis: Int,
) : AasdkByteTransport {
    private val open = AtomicBoolean(true)
    private val readLock = Any()
    private val writeLock = Any()

    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            ensureOpen()
            var offset = 0
            while (offset < data.size) {
                val requested = minOf(MAX_USB_TRANSFER_BYTES, data.size - offset)
                val transferred = try {
                    io.write(data, offset, requested, writeTimeoutMillis)
                } catch (error: Exception) {
                    throw AoaBulkTransferException(
                        code = AoaBulkTransferErrorCode.WRITE_TIMEOUT_STALL_OR_IO_FAILURE,
                        requestedBytes = requested,
                        cause = error,
                    )
                }
                when {
                    transferred < 0 -> throw AoaBulkTransferException(
                        code = AoaBulkTransferErrorCode.WRITE_TIMEOUT_STALL_OR_IO_FAILURE,
                        requestedBytes = requested,
                        transferredBytes = transferred,
                    )

                    transferred != requested -> throw AoaBulkTransferException(
                        code = AoaBulkTransferErrorCode.WRITE_INCOMPLETE,
                        requestedBytes = requested,
                        transferredBytes = transferred,
                    )
                }
                offset += transferred
            }
        }
    }

    override suspend fun readExactly(size: Int): ByteArray = withContext(Dispatchers.IO) {
        if (size < 0 || size > AasdkFrameCodec.MAX_MESSAGE_SIZE) {
            throw AoaBulkTransferException(
                code = AoaBulkTransferErrorCode.INVALID_READ_SIZE,
                requestedBytes = size,
            )
        }
        synchronized(readLock) {
            ensureOpen()
            val output = ByteArray(size)
            var offset = 0
            while (offset < output.size) {
                val requested = minOf(MAX_USB_TRANSFER_BYTES, output.size - offset)
                val transferred = try {
                    io.read(output, offset, requested, readTimeoutMillis)
                } catch (error: Exception) {
                    throw AoaBulkTransferException(
                        code = AoaBulkTransferErrorCode.READ_TIMEOUT_STALL_OR_IO_FAILURE,
                        requestedBytes = requested,
                        cause = error,
                    )
                }
                when {
                    transferred <= 0 -> throw AoaBulkTransferException(
                        code = AoaBulkTransferErrorCode.READ_TIMEOUT_STALL_OR_IO_FAILURE,
                        requestedBytes = requested,
                        transferredBytes = transferred,
                    )

                    transferred > requested -> throw AoaBulkTransferException(
                        code = AoaBulkTransferErrorCode.READ_RESULT_INVALID,
                        requestedBytes = requested,
                        transferredBytes = transferred,
                    )
                }
                offset += transferred
            }
            output
        }
    }

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        synchronized(readLock) {
            synchronized(writeLock) {
                io.close()
            }
        }
    }

    private fun ensureOpen() {
        if (!open.get()) {
            throw AoaBulkTransferException(AoaBulkTransferErrorCode.CLOSED)
        }
    }

    internal companion object {
        // Android versions before P may truncate larger UsbDeviceConnection requests.
        const val MAX_USB_TRANSFER_BYTES = 16 * 1024
    }
}
