/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.usb

import java.net.URI

/** A stable key for one device chosen by the user or service. */
data class AoaUsbDeviceSelection(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
) {
    fun matches(
        currentDeviceName: String,
        currentDeviceId: Int,
        currentVendorId: Int,
        currentProductId: Int,
    ): Boolean =
        deviceName == currentDeviceName &&
            deviceId == currentDeviceId &&
            vendorId == currentVendorId &&
            productId == currentProductId
}

enum class AoaAccessoryMode(
    val carriesAccessoryData: Boolean,
    val adbEnabled: Boolean,
    val audioEnabled: Boolean,
) {
    ACCESSORY(carriesAccessoryData = true, adbEnabled = false, audioEnabled = false),
    ACCESSORY_ADB(carriesAccessoryData = true, adbEnabled = true, audioEnabled = false),
    AUDIO(carriesAccessoryData = false, adbEnabled = false, audioEnabled = true),
    AUDIO_ADB(carriesAccessoryData = false, adbEnabled = true, audioEnabled = true),
    ACCESSORY_AUDIO(carriesAccessoryData = true, adbEnabled = false, audioEnabled = true),
    ACCESSORY_AUDIO_ADB(carriesAccessoryData = true, adbEnabled = true, audioEnabled = true),
}

enum class AoaEndpointDirection {
    IN,
    OUT,
}

data class AoaEndpointShape(
    val direction: AoaEndpointDirection,
    val isBulk: Boolean,
)

data class AoaInterfaceShape(
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<AoaEndpointShape>,
)

/** Exact indexes of the one AOA data interface and its bulk endpoint pair. */
internal data class AoaBulkEndpointSelection(
    val interfaceIndex: Int,
    val bulkInEndpointIndex: Int,
    val bulkOutEndpointIndex: Int,
)

internal sealed interface AoaBulkEndpointSelectionResult {
    data class Ready(val selection: AoaBulkEndpointSelection) : AoaBulkEndpointSelectionResult
    data object InterfaceMissing : AoaBulkEndpointSelectionResult
    data object InterfaceAmbiguous : AoaBulkEndpointSelectionResult
    data object EndpointMissing : AoaBulkEndpointSelectionResult
    data object EndpointAmbiguous : AoaBulkEndpointSelectionResult
}

data class AoaAccessoryIdentity(
    val manufacturer: String,
    val model: String,
    val description: String = "",
    val version: String,
    val uri: String = "",
    val serial: String = "",
)

enum class AoaIdentityField(val requestIndex: Int, val required: Boolean) {
    MANUFACTURER(requestIndex = 0, required = true),
    MODEL(requestIndex = 1, required = true),
    DESCRIPTION(requestIndex = 2, required = false),
    VERSION(requestIndex = 3, required = true),
    URI(requestIndex = 4, required = false),
    SERIAL(requestIndex = 5, required = false),
}

enum class AoaUsbStatusCode {
    DISCOVERY_READY,
    HOST_UNSUPPORTED,
    NO_USB_DEVICES,
    DEVICE_NOT_FOUND,
    DEVICE_CHANGED,
    SAFETY_CONFIRMATION_REQUIRED,
    IDENTITY_INVALID,
    PERMISSION_REQUIRED,
    PERMISSION_REQUESTED,
    PERMISSION_REQUEST_FAILED,
    STANDARD_DEVICE_READY,
    ACCESSORY_READY,
    ACCESSORY_DATA_UNAVAILABLE,
    ACCESSORY_INTERFACE_MISSING,
    ACCESSORY_INTERFACE_AMBIGUOUS,
    ACCESSORY_ENDPOINT_MISSING,
    ACCESSORY_ENDPOINT_AMBIGUOUS,
    DEVICE_OPEN_FAILED,
    BULK_TRANSPORT_CONFIGURATION_INVALID,
    INTERFACE_CLAIM_FAILED,
    BULK_TRANSPORT_READY,
    PROTOCOL_QUERY_FAILED,
    PROTOCOL_RESPONSE_INVALID,
    PROTOCOL_UNSUPPORTED,
    IDENTITY_TRANSFER_FAILED,
    START_TRANSFER_FAILED,
    MODE_SWITCH_REQUESTED,
}

/**
 * Contains only non-secret diagnostics. Device paths and accessory serial strings are
 * deliberately excluded from this value so it is safe to show in the UI or logs.
 */
data class AoaUsbStatus(
    val code: AoaUsbStatusCode,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val protocolVersion: Int? = null,
    val identityField: AoaIdentityField? = null,
) {
    fun safeSummary(): String = buildString {
        append(code.name)
        if (vendorId != null && productId != null) {
            append(" [")
            append(vendorId.toString(16).padStart(4, '0').uppercase())
            append(':')
            append(productId.toString(16).padStart(4, '0').uppercase())
            append(']')
        }
        protocolVersion?.let { append(" protocol=").append(it) }
        identityField?.let { append(" field=").append(it.name) }
    }
}

data class AoaUsbCandidate(
    val selection: AoaUsbDeviceSelection,
    val accessoryMode: AoaAccessoryMode?,
    val accessoryDataInterfacePresent: Boolean,
    val permissionGranted: Boolean,
) {
    fun safeSummary(): String =
        "%04X:%04X mode=%s permission=%s".format(
            selection.vendorId,
            selection.productId,
            accessoryMode?.name ?: "STANDARD",
            permissionGranted,
        )
}

data class AoaUsbDiscovery(
    val status: AoaUsbStatus,
    val candidates: List<AoaUsbCandidate>,
)

data class AoaNegotiationRequest(
    val selectedDevice: AoaUsbDeviceSelection,
    val identity: AoaAccessoryIdentity,
    /** Must be supplied only after the existing bench/parked safety gate succeeds. */
    val safetyGateConfirmed: Boolean,
)

internal data class AoaEncodedIdentityField(
    val field: AoaIdentityField,
    val bytesWithTerminator: ByteArray,
)

internal sealed interface AoaRequestValidation {
    data class Ready(val identityFields: List<AoaEncodedIdentityField>) : AoaRequestValidation
    data class Rejected(val status: AoaUsbStatus) : AoaRequestValidation
}

internal sealed interface AoaProtocolResponse {
    data class Valid(val version: Int) : AoaProtocolResponse
    data object Invalid : AoaProtocolResponse
}

/** Pure AOA protocol and policy logic; intentionally independent of Android APIs. */
internal object AoaUsbLogic {
    const val GOOGLE_ACCESSORY_VENDOR_ID = 0x18D1
    const val ACCESSORY_INTERFACE_CLASS = 0xFF
    const val ACCESSORY_INTERFACE_SUBCLASS = 0xFF
    const val ACCESSORY_INTERFACE_PROTOCOL = 0
    const val MIN_SUPPORTED_PROTOCOL_VERSION = 1
    const val MAX_SUPPORTED_PROTOCOL_VERSION = 2

    private const val MAX_STRING_TRANSFER_BYTES = 256

    fun accessoryMode(vendorId: Int, productId: Int): AoaAccessoryMode? {
        if (vendorId != GOOGLE_ACCESSORY_VENDOR_ID) return null
        return when (productId) {
            0x2D00 -> AoaAccessoryMode.ACCESSORY
            0x2D01 -> AoaAccessoryMode.ACCESSORY_ADB
            0x2D02 -> AoaAccessoryMode.AUDIO
            0x2D03 -> AoaAccessoryMode.AUDIO_ADB
            0x2D04 -> AoaAccessoryMode.ACCESSORY_AUDIO
            0x2D05 -> AoaAccessoryMode.ACCESSORY_AUDIO_ADB
            else -> null
        }
    }

    fun hasAccessoryDataInterface(interfaces: List<AoaInterfaceShape>): Boolean =
        selectAccessoryBulkEndpoints(interfaces) is AoaBulkEndpointSelectionResult.Ready

    /**
     * Resolves a unique AOA interface and a unique bulk IN/OUT endpoint pair.
     * Ambiguous descriptors fail closed rather than choosing the first USB entry.
     */
    fun selectAccessoryBulkEndpoints(
        interfaces: List<AoaInterfaceShape>,
    ): AoaBulkEndpointSelectionResult {
        val accessoryInterfaces = interfaces.withIndex().filter { (_, usbInterface) ->
            usbInterface.interfaceClass == ACCESSORY_INTERFACE_CLASS &&
                usbInterface.interfaceSubclass == ACCESSORY_INTERFACE_SUBCLASS &&
                usbInterface.interfaceProtocol == ACCESSORY_INTERFACE_PROTOCOL
        }
        if (accessoryInterfaces.isEmpty()) {
            return AoaBulkEndpointSelectionResult.InterfaceMissing
        }

        var endpointAmbiguous = false
        val complete = accessoryInterfaces.mapNotNull { (interfaceIndex, usbInterface) ->
            val bulkIn = usbInterface.endpoints.withIndex().filter { (_, endpoint) ->
                endpoint.isBulk && endpoint.direction == AoaEndpointDirection.IN
            }
            val bulkOut = usbInterface.endpoints.withIndex().filter { (_, endpoint) ->
                endpoint.isBulk && endpoint.direction == AoaEndpointDirection.OUT
            }
            if (bulkIn.size > 1 || bulkOut.size > 1) endpointAmbiguous = true
            if (bulkIn.size != 1 || bulkOut.size != 1) {
                null
            } else {
                AoaBulkEndpointSelection(
                    interfaceIndex = interfaceIndex,
                    bulkInEndpointIndex = bulkIn.single().index,
                    bulkOutEndpointIndex = bulkOut.single().index,
                )
            }
        }
        return when {
            complete.size > 1 -> AoaBulkEndpointSelectionResult.InterfaceAmbiguous
            complete.size == 1 && !endpointAmbiguous ->
                AoaBulkEndpointSelectionResult.Ready(complete.single())
            endpointAmbiguous -> AoaBulkEndpointSelectionResult.EndpointAmbiguous
            else -> AoaBulkEndpointSelectionResult.EndpointMissing
        }
    }

    fun decodeProtocolResponse(transferred: Int, response: ByteArray): AoaProtocolResponse {
        if (transferred != 2 || response.size < 2) return AoaProtocolResponse.Invalid
        val version = (response[0].toInt() and 0xFF) or
            ((response[1].toInt() and 0xFF) shl 8)
        return AoaProtocolResponse.Valid(version)
    }

    /** Matches the fixed upstream OpenAuto/AASDK accessory-mode version policy. */
    fun isSupportedProtocolVersion(version: Int): Boolean =
        version in MIN_SUPPORTED_PROTOCOL_VERSION..MAX_SUPPORTED_PROTOCOL_VERSION

    fun validateRequest(request: AoaNegotiationRequest): AoaRequestValidation {
        if (!request.safetyGateConfirmed) {
            return AoaRequestValidation.Rejected(
                AoaUsbStatus(AoaUsbStatusCode.SAFETY_CONFIRMATION_REQUIRED),
            )
        }

        val values = listOf(
            AoaIdentityField.MANUFACTURER to request.identity.manufacturer,
            AoaIdentityField.MODEL to request.identity.model,
            AoaIdentityField.DESCRIPTION to request.identity.description,
            AoaIdentityField.VERSION to request.identity.version,
            AoaIdentityField.URI to request.identity.uri,
            AoaIdentityField.SERIAL to request.identity.serial,
        )
        val encoded = ArrayList<AoaEncodedIdentityField>(values.size)
        for ((field, value) in values) {
            if (value.isEmpty() && !field.required) continue
            if (value.isBlank() || value.indexOf('\u0000') >= 0) {
                return invalidIdentity(field)
            }
            if (field == AoaIdentityField.URI && !isSupportedUri(value)) {
                return invalidIdentity(field)
            }
            val utf8 = value.toByteArray(Charsets.UTF_8)
            if (utf8.size + 1 > MAX_STRING_TRANSFER_BYTES) {
                return invalidIdentity(field)
            }
            encoded += AoaEncodedIdentityField(field, utf8 + byteArrayOf(0))
        }
        return AoaRequestValidation.Ready(encoded)
    }

    private fun invalidIdentity(field: AoaIdentityField): AoaRequestValidation.Rejected =
        AoaRequestValidation.Rejected(
            AoaUsbStatus(
                code = AoaUsbStatusCode.IDENTITY_INVALID,
                identityField = field,
            ),
        )

    private fun isSupportedUri(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
