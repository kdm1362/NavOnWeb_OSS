package com.pebble.tecomheadunit.openauto.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AoaProtocolTest {
    @Test
    fun accessoryProductIdsAreClassifiedWithoutGuessingOtherGoogleDevices() {
        assertEquals(
            AoaAccessoryMode.ACCESSORY_ADB,
            AoaUsbLogic.accessoryMode(0x18D1, 0x2D01),
        )
        assertEquals(
            AoaAccessoryMode.ACCESSORY_AUDIO,
            AoaUsbLogic.accessoryMode(0x18D1, 0x2D04),
        )
        assertEquals(null, AoaUsbLogic.accessoryMode(0x18D1, 0x4EE7))
        assertEquals(null, AoaUsbLogic.accessoryMode(0x1234, 0x2D00))
    }

    @Test
    fun aoaDataInterfaceRequiresBulkPairAndDoesNotAcceptAdbInterface() {
        val adbOnly = AoaInterfaceShape(
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 1,
            endpoints = bulkPair(),
        )
        assertFalse(AoaUsbLogic.hasAccessoryDataInterface(listOf(adbOnly)))

        val accessory = AoaInterfaceShape(
            interfaceClass = 0xFF,
            interfaceSubclass = 0xFF,
            interfaceProtocol = 0,
            endpoints = bulkPair(),
        )
        assertTrue(AoaUsbLogic.hasAccessoryDataInterface(listOf(adbOnly, accessory)))
    }

    @Test
    fun uniqueAccessoryInterfaceAndEndpointIndexesAreResolvedExactly() {
        val adbOnly = AoaInterfaceShape(
            interfaceClass = 0xFF,
            interfaceSubclass = 0x42,
            interfaceProtocol = 1,
            endpoints = bulkPair(),
        )
        val accessory = AoaInterfaceShape(
            interfaceClass = 0xFF,
            interfaceSubclass = 0xFF,
            interfaceProtocol = 0,
            endpoints = listOf(
                AoaEndpointShape(AoaEndpointDirection.OUT, isBulk = false),
                AoaEndpointShape(AoaEndpointDirection.OUT, isBulk = true),
                AoaEndpointShape(AoaEndpointDirection.IN, isBulk = true),
            ),
        )

        val result = AoaUsbLogic.selectAccessoryBulkEndpoints(listOf(adbOnly, accessory))

        assertEquals(
            AoaBulkEndpointSelectionResult.Ready(
                AoaBulkEndpointSelection(
                    interfaceIndex = 1,
                    bulkInEndpointIndex = 2,
                    bulkOutEndpointIndex = 1,
                ),
            ),
            result,
        )
    }

    @Test
    fun ambiguousAccessoryDescriptorsAndEndpointsFailClosed() {
        val accessory = AoaInterfaceShape(
            interfaceClass = 0xFF,
            interfaceSubclass = 0xFF,
            interfaceProtocol = 0,
            endpoints = bulkPair(),
        )
        assertEquals(
            AoaBulkEndpointSelectionResult.InterfaceAmbiguous,
            AoaUsbLogic.selectAccessoryBulkEndpoints(listOf(accessory, accessory)),
        )

        val duplicateIn = accessory.copy(
            endpoints = bulkPair() + AoaEndpointShape(AoaEndpointDirection.IN, isBulk = true),
        )
        assertEquals(
            AoaBulkEndpointSelectionResult.EndpointAmbiguous,
            AoaUsbLogic.selectAccessoryBulkEndpoints(listOf(duplicateIn)),
        )
        assertFalse(AoaUsbLogic.hasAccessoryDataInterface(listOf(duplicateIn)))
    }

    @Test
    fun protocolResponseIsExactlyTwoByteLittleEndian() {
        assertEquals(
            AoaProtocolResponse.Valid(2),
            AoaUsbLogic.decodeProtocolResponse(2, byteArrayOf(2, 0)),
        )
        assertEquals(
            AoaProtocolResponse.Invalid,
            AoaUsbLogic.decodeProtocolResponse(1, byteArrayOf(2, 0)),
        )
    }

    @Test
    fun upstreamAccessoryProtocolVersionsOneAndTwoAreSupported() {
        assertTrue(AoaUsbLogic.isSupportedProtocolVersion(1))
        assertTrue(AoaUsbLogic.isSupportedProtocolVersion(2))
        assertFalse(AoaUsbLogic.isSupportedProtocolVersion(0))
        assertFalse(AoaUsbLogic.isSupportedProtocolVersion(3))
    }

    @Test
    fun negotiationFailsClosedBeforeUsbWhenSafetyGateWasNotConfirmed() {
        val request = validRequest(safetyGateConfirmed = false)

        val result = AoaUsbLogic.validateRequest(request)

        assertTrue(result is AoaRequestValidation.Rejected)
        assertEquals(
            AoaUsbStatusCode.SAFETY_CONFIRMATION_REQUIRED,
            (result as AoaRequestValidation.Rejected).status.code,
        )
    }

    @Test
    fun identityUsesNullTerminatedUtf8AndSkipsEmptyOptionalFields() {
        val result = AoaUsbLogic.validateRequest(validRequest(safetyGateConfirmed = true))

        assertTrue(result is AoaRequestValidation.Ready)
        val fields = (result as AoaRequestValidation.Ready).identityFields
        assertEquals(
            listOf(
                AoaIdentityField.MANUFACTURER,
                AoaIdentityField.MODEL,
                AoaIdentityField.VERSION,
            ),
            fields.map { it.field },
        )
        assertTrue(fields.all { it.bytesWithTerminator.last() == 0.toByte() })
    }

    @Test
    fun invalidUriAndEmbeddedNullAreRejectedWithFieldCode() {
        val badUri = validRequest(
            identity = validIdentity().copy(uri = "file:///tmp/accessory"),
        )
        val badNull = validRequest(
            identity = validIdentity().copy(model = "Head\u0000Unit"),
        )

        assertRejectedField(badUri, AoaIdentityField.URI)
        assertRejectedField(badNull, AoaIdentityField.MODEL)
    }

    @Test
    fun identityStringCannotExceedOneControlTransferField() {
        val request = validRequest(
            identity = validIdentity().copy(model = "a".repeat(256)),
        )

        assertRejectedField(request, AoaIdentityField.MODEL)
    }

    @Test
    fun deviceSelectionRejectsReenumeratedOrReplacedDevice() {
        val selected = AoaUsbDeviceSelection("/dev/bus/usb/001/002", 7, 0x18D1, 0x4EE7)

        assertTrue(selected.matches("/dev/bus/usb/001/002", 7, 0x18D1, 0x4EE7))
        assertFalse(selected.matches("/dev/bus/usb/001/002", 8, 0x18D1, 0x4EE7))
        assertFalse(selected.matches("/dev/bus/usb/001/002", 7, 0x1234, 0x4EE7))
    }

    private fun assertRejectedField(
        request: AoaNegotiationRequest,
        expected: AoaIdentityField,
    ) {
        val result = AoaUsbLogic.validateRequest(request)
        assertTrue(result is AoaRequestValidation.Rejected)
        val status = (result as AoaRequestValidation.Rejected).status
        assertEquals(AoaUsbStatusCode.IDENTITY_INVALID, status.code)
        assertEquals(expected, status.identityField)
    }

    private fun validRequest(
        safetyGateConfirmed: Boolean = true,
        identity: AoaAccessoryIdentity = validIdentity(),
    ): AoaNegotiationRequest = AoaNegotiationRequest(
        selectedDevice = AoaUsbDeviceSelection("selected-device", 1, 0x18D1, 0x4EE7),
        identity = identity,
        safetyGateConfirmed = safetyGateConfirmed,
    )

    private fun validIdentity(): AoaAccessoryIdentity = AoaAccessoryIdentity(
        manufacturer = "Example Manufacturer",
        model = "Example Head Unit",
        version = "0.1",
    )

    private fun bulkPair(): List<AoaEndpointShape> = listOf(
        AoaEndpointShape(AoaEndpointDirection.IN, isBulk = true),
        AoaEndpointShape(AoaEndpointDirection.OUT, isBulk = true),
    )
}
