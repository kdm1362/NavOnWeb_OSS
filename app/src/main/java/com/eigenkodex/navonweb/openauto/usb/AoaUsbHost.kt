/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.usb

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/**
 * Bounded Android USB Host adapter for the AOA 1.0/2.0 negotiation accepted by OpenAuto/AASDK.
 *
 * Construction, [discover], and [preflight] never send USB control transfers. A caller must
 * select one exact device and explicitly call [negotiateBlocking] after its safety gate passes.
 * The call is blocking and must run on a worker dispatcher. A successful result only means that
 * the phone was asked to re-enumerate in accessory mode; it is not an Android Auto session.
 */
class AoaUsbHost(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager: UsbManager? = appContext.getSystemService(UsbManager::class.java)

    fun discover(): AoaUsbDiscovery {
        if (!hostSupported()) {
            return AoaUsbDiscovery(
                status = AoaUsbStatus(AoaUsbStatusCode.HOST_UNSUPPORTED),
                candidates = emptyList(),
            )
        }
        val manager = requireNotNull(usbManager)
        val candidates = manager.deviceList.values
            .map { device ->
                AoaUsbCandidate(
                    selection = device.toSelection(),
                    accessoryMode = AoaUsbLogic.accessoryMode(device.vendorId, device.productId),
                    accessoryDataInterfacePresent = AoaUsbLogic.hasAccessoryDataInterface(
                        device.interfaceShapes(),
                    ),
                    permissionGranted = manager.hasPermission(device),
                )
            }
            .sortedWith(
                compareBy(
                    { it.selection.vendorId },
                    { it.selection.productId },
                    { it.selection.deviceId },
                ),
            )
        val code = if (candidates.isEmpty()) {
            AoaUsbStatusCode.NO_USB_DEVICES
        } else {
            AoaUsbStatusCode.DISCOVERY_READY
        }
        return AoaUsbDiscovery(AoaUsbStatus(code), candidates)
    }

    /** Read-only check of the exact device selected earlier by the caller. */
    fun preflight(selection: AoaUsbDeviceSelection): AoaUsbStatus {
        if (!hostSupported()) return AoaUsbStatus(AoaUsbStatusCode.HOST_UNSUPPORTED)
        val resolved = resolve(selection)
        if (resolved.status != null) return resolved.status
        return preflightResolved(requireNotNull(resolved.device))
    }

    /**
     * Requests Android USB permission for one exact selected device. This does not negotiate or
     * switch USB modes. The caller owns the PendingIntent receiver and must re-run [preflight].
     */
    fun requestPermission(
        selection: AoaUsbDeviceSelection,
        permissionResult: PendingIntent,
    ): AoaUsbStatus {
        if (!hostSupported()) return AoaUsbStatus(AoaUsbStatusCode.HOST_UNSUPPORTED)
        val resolved = resolve(selection)
        if (resolved.status != null) return resolved.status
        val device = requireNotNull(resolved.device)
        val manager = requireNotNull(usbManager)
        if (manager.hasPermission(device)) return preflightResolved(device)
        return runCatching {
            manager.requestPermission(device, permissionResult)
            device.status(AoaUsbStatusCode.PERMISSION_REQUESTED)
        }.getOrElse {
            device.status(AoaUsbStatusCode.PERMISSION_REQUEST_FAILED)
        }
    }

    /**
     * Executes the AOA control-transfer sequence for one explicitly selected device.
     * No transfer occurs when [AoaNegotiationRequest.safetyGateConfirmed] is false.
     */
    fun negotiateBlocking(request: AoaNegotiationRequest): AoaUsbStatus {
        val validation = AoaUsbLogic.validateRequest(request)
        if (validation is AoaRequestValidation.Rejected) return validation.status
        validation as AoaRequestValidation.Ready

        if (!hostSupported()) return AoaUsbStatus(AoaUsbStatusCode.HOST_UNSUPPORTED)
        val resolved = resolve(request.selectedDevice)
        if (resolved.status != null) return resolved.status
        val device = requireNotNull(resolved.device)

        val mode = AoaUsbLogic.accessoryMode(device.vendorId, device.productId)
        if (mode != null) return preflightResolved(device)

        val manager = requireNotNull(usbManager)
        if (!manager.hasPermission(device)) {
            return device.status(AoaUsbStatusCode.PERMISSION_REQUIRED)
        }

        val connection = runCatching { manager.openDevice(device) }.getOrNull()
            ?: return device.status(AoaUsbStatusCode.DEVICE_OPEN_FAILED)
        return connection.useForAoa(device, validation.identityFields)
    }

    /**
     * Opens the exact accessory device selected after AOA re-enumeration and claims its unique
     * data interface. This method never discovers or substitutes another device automatically.
     * It blocks and must run on a worker dispatcher.
     */
    internal fun openBulkTransportBlocking(
        request: AoaBulkTransportOpenRequest,
    ): AoaBulkTransportOpenResult {
        validateBulkTransportOpenRequest(request)?.let { validationFailure ->
            return AoaBulkTransportOpenResult.Rejected(validationFailure)
        }
        if (!hostSupported()) {
            return AoaBulkTransportOpenResult.Rejected(
                AoaUsbStatus(AoaUsbStatusCode.HOST_UNSUPPORTED),
            )
        }

        val resolved = resolve(request.selectedAccessoryDevice)
        if (resolved.status != null) {
            return AoaBulkTransportOpenResult.Rejected(resolved.status)
        }
        val device = requireNotNull(resolved.device)
        val mode = AoaUsbLogic.accessoryMode(device.vendorId, device.productId)
        if (mode?.carriesAccessoryData != true) {
            return AoaBulkTransportOpenResult.Rejected(
                device.status(AoaUsbStatusCode.ACCESSORY_DATA_UNAVAILABLE),
            )
        }

        val manager = requireNotNull(usbManager)
        if (!manager.hasPermission(device)) {
            return AoaBulkTransportOpenResult.Rejected(
                device.status(AoaUsbStatusCode.PERMISSION_REQUIRED),
            )
        }
        val endpointSelection = when (
            val selection = AoaUsbLogic.selectAccessoryBulkEndpoints(device.interfaceShapes())
        ) {
            is AoaBulkEndpointSelectionResult.Ready -> selection.selection
            AoaBulkEndpointSelectionResult.InterfaceMissing -> {
                return AoaBulkTransportOpenResult.Rejected(
                    device.status(AoaUsbStatusCode.ACCESSORY_INTERFACE_MISSING),
                )
            }
            AoaBulkEndpointSelectionResult.InterfaceAmbiguous -> {
                return AoaBulkTransportOpenResult.Rejected(
                    device.status(AoaUsbStatusCode.ACCESSORY_INTERFACE_AMBIGUOUS),
                )
            }
            AoaBulkEndpointSelectionResult.EndpointMissing -> {
                return AoaBulkTransportOpenResult.Rejected(
                    device.status(AoaUsbStatusCode.ACCESSORY_ENDPOINT_MISSING),
                )
            }
            AoaBulkEndpointSelectionResult.EndpointAmbiguous -> {
                return AoaBulkTransportOpenResult.Rejected(
                    device.status(AoaUsbStatusCode.ACCESSORY_ENDPOINT_AMBIGUOUS),
                )
            }
        }

        val usbInterface = device.getInterface(endpointSelection.interfaceIndex)
        val bulkIn = usbInterface.getEndpoint(endpointSelection.bulkInEndpointIndex)
        val bulkOut = usbInterface.getEndpoint(endpointSelection.bulkOutEndpointIndex)
        val connection = runCatching { manager.openDevice(device) }.getOrNull()
            ?: return AoaBulkTransportOpenResult.Rejected(
                device.status(AoaUsbStatusCode.DEVICE_OPEN_FAILED),
            )
        val claimed = runCatching { connection.claimInterface(usbInterface, true) }
            .getOrDefault(false)
        if (!claimed) {
            connection.close()
            return AoaBulkTransportOpenResult.Rejected(
                device.status(AoaUsbStatusCode.INTERFACE_CLAIM_FAILED),
            )
        }

        val io = AndroidAoaBulkIo(
            connection = connection,
            usbInterface = usbInterface,
            bulkIn = bulkIn,
            bulkOut = bulkOut,
        )
        return AoaBulkTransportOpenResult.Ready(
            status = device.status(AoaUsbStatusCode.BULK_TRANSPORT_READY),
            transport = AoaUsbBulkTransport(
                io = io,
                readTimeoutMillis = request.readTimeoutMillis,
                writeTimeoutMillis = request.writeTimeoutMillis,
            ),
        )
    }

    private fun UsbDeviceConnection.useForAoa(
        device: UsbDevice,
        identityFields: List<AoaEncodedIdentityField>,
    ): AoaUsbStatus = try {
        val response = ByteArray(PROTOCOL_RESPONSE_SIZE)
        val transferred = runCatching {
            controlTransfer(
                REQUEST_TYPE_VENDOR_IN,
                REQUEST_GET_PROTOCOL,
                0,
                0,
                response,
                response.size,
                CONTROL_TRANSFER_TIMEOUT_MS,
            )
        }.getOrElse { -1 }
        if (transferred < 0) {
            return device.status(AoaUsbStatusCode.PROTOCOL_QUERY_FAILED)
        }
        val protocol = AoaUsbLogic.decodeProtocolResponse(transferred, response)
        if (protocol !is AoaProtocolResponse.Valid) {
            return device.status(AoaUsbStatusCode.PROTOCOL_RESPONSE_INVALID)
        }
        if (!AoaUsbLogic.isSupportedProtocolVersion(protocol.version)) {
            return device.status(
                code = AoaUsbStatusCode.PROTOCOL_UNSUPPORTED,
                protocolVersion = protocol.version,
            )
        }

        for (identityField in identityFields) {
            val fieldTransferred = runCatching {
                controlTransfer(
                    REQUEST_TYPE_VENDOR_OUT,
                    REQUEST_SEND_STRING,
                    0,
                    identityField.field.requestIndex,
                    identityField.bytesWithTerminator,
                    identityField.bytesWithTerminator.size,
                    CONTROL_TRANSFER_TIMEOUT_MS,
                )
            }.getOrElse { -1 }
            if (fieldTransferred != identityField.bytesWithTerminator.size) {
                return device.status(
                    code = AoaUsbStatusCode.IDENTITY_TRANSFER_FAILED,
                    protocolVersion = protocol.version,
                    identityField = identityField.field,
                )
            }
        }

        val startTransferred = runCatching {
            controlTransfer(
                REQUEST_TYPE_VENDOR_OUT,
                REQUEST_START_ACCESSORY,
                0,
                0,
                null,
                0,
                CONTROL_TRANSFER_TIMEOUT_MS,
            )
        }.getOrElse { -1 }
        if (startTransferred != 0) {
            return device.status(
                code = AoaUsbStatusCode.START_TRANSFER_FAILED,
                protocolVersion = protocol.version,
            )
        }
        device.status(
            code = AoaUsbStatusCode.MODE_SWITCH_REQUESTED,
            protocolVersion = protocol.version,
        )
    } finally {
        close()
    }

    private fun preflightResolved(device: UsbDevice): AoaUsbStatus {
        val manager = requireNotNull(usbManager)
        val mode = AoaUsbLogic.accessoryMode(device.vendorId, device.productId)
        if (mode == null) {
            return if (manager.hasPermission(device)) {
                device.status(AoaUsbStatusCode.STANDARD_DEVICE_READY)
            } else {
                device.status(AoaUsbStatusCode.PERMISSION_REQUIRED)
            }
        }
        if (!mode.carriesAccessoryData) {
            return device.status(AoaUsbStatusCode.ACCESSORY_DATA_UNAVAILABLE)
        }
        when (AoaUsbLogic.selectAccessoryBulkEndpoints(device.interfaceShapes())) {
            is AoaBulkEndpointSelectionResult.Ready -> Unit
            AoaBulkEndpointSelectionResult.InterfaceMissing ->
                return device.status(AoaUsbStatusCode.ACCESSORY_INTERFACE_MISSING)
            AoaBulkEndpointSelectionResult.InterfaceAmbiguous ->
                return device.status(AoaUsbStatusCode.ACCESSORY_INTERFACE_AMBIGUOUS)
            AoaBulkEndpointSelectionResult.EndpointMissing ->
                return device.status(AoaUsbStatusCode.ACCESSORY_ENDPOINT_MISSING)
            AoaBulkEndpointSelectionResult.EndpointAmbiguous ->
                return device.status(AoaUsbStatusCode.ACCESSORY_ENDPOINT_AMBIGUOUS)
        }
        return if (manager.hasPermission(device)) {
            device.status(AoaUsbStatusCode.ACCESSORY_READY)
        } else {
            device.status(AoaUsbStatusCode.PERMISSION_REQUIRED)
        }
    }

    private fun hostSupported(): Boolean =
        usbManager != null &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private fun resolve(selection: AoaUsbDeviceSelection): ResolvedDevice {
        val current = requireNotNull(usbManager).deviceList[selection.deviceName]
            ?: return ResolvedDevice(
                status = AoaUsbStatus(
                    code = AoaUsbStatusCode.DEVICE_NOT_FOUND,
                    vendorId = selection.vendorId,
                    productId = selection.productId,
                ),
            )
        if (!selection.matches(
                currentDeviceName = current.deviceName,
                currentDeviceId = current.deviceId,
                currentVendorId = current.vendorId,
                currentProductId = current.productId,
            )
        ) {
            return ResolvedDevice(
                status = AoaUsbStatus(
                    code = AoaUsbStatusCode.DEVICE_CHANGED,
                    vendorId = selection.vendorId,
                    productId = selection.productId,
                ),
            )
        }
        return ResolvedDevice(device = current)
    }

    private fun UsbDevice.toSelection(): AoaUsbDeviceSelection = AoaUsbDeviceSelection(
        deviceName = deviceName,
        deviceId = deviceId,
        vendorId = vendorId,
        productId = productId,
    )

    private fun UsbDevice.interfaceShapes(): List<AoaInterfaceShape> =
        (0 until interfaceCount).map { interfaceIndex ->
            val usbInterface = getInterface(interfaceIndex)
            AoaInterfaceShape(
                interfaceClass = usbInterface.interfaceClass,
                interfaceSubclass = usbInterface.interfaceSubclass,
                interfaceProtocol = usbInterface.interfaceProtocol,
                endpoints = (0 until usbInterface.endpointCount).map { endpointIndex ->
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    AoaEndpointShape(
                        direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                            AoaEndpointDirection.IN
                        } else {
                            AoaEndpointDirection.OUT
                        },
                        isBulk = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK,
                    )
                },
            )
        }

    private fun UsbDevice.status(
        code: AoaUsbStatusCode,
        protocolVersion: Int? = null,
        identityField: AoaIdentityField? = null,
    ): AoaUsbStatus = AoaUsbStatus(
        code = code,
        vendorId = vendorId,
        productId = productId,
        protocolVersion = protocolVersion,
        identityField = identityField,
    )

    private data class ResolvedDevice(
        val device: UsbDevice? = null,
        val status: AoaUsbStatus? = null,
    )

    private companion object {
        const val REQUEST_TYPE_VENDOR_IN = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR
        const val REQUEST_TYPE_VENDOR_OUT = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR
        const val REQUEST_GET_PROTOCOL = 51
        const val REQUEST_SEND_STRING = 52
        const val REQUEST_START_ACCESSORY = 53
        const val PROTOCOL_RESPONSE_SIZE = 2
        const val CONTROL_TRANSFER_TIMEOUT_MS = 1_000
    }
}
