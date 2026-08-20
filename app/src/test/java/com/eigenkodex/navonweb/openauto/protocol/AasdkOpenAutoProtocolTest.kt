/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.protocol

import com.eigenkodex.navonweb.core.OpenAutoTouchEvent
import com.eigenkodex.navonweb.core.TouchPhase
import com.eigenkodex.navonweb.core.VideoViewport
import com.eigenkodex.navonweb.openauto.OpenAutoConfig
import com.eigenkodex.navonweb.openauto.OpenAutoKeyAction
import com.eigenkodex.navonweb.openauto.OpenAutoKeyCode
import com.eigenkodex.navonweb.openauto.OpenAutoKeyEvent
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import com.eigenkodex.navonweb.openauto.ProjectionViewportResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AasdkOpenAutoProtocolTest {
    @Test
    fun `night sensor indication encodes outer field ten and inner bool field one`() {
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x03, 0x52, 0x02, 0x08, 0x00),
            AasdkOpenAutoProtocol.nightMode(isNight = false),
        )
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0x03, 0x52, 0x02, 0x08, 0x01),
            AasdkOpenAutoProtocol.nightMode(isNight = true),
        )
        assertArrayEquals(AasdkOpenAutoProtocol.nightMode(false), AasdkOpenAutoProtocol.dayMode())
    }

    @Test
    fun `service discovery advertises the exact key allowlist`() {
        val message = AasdkOpenAutoProtocol.serviceDiscoveryResponse()

        assertEquals(AasdkOpenAutoProtocol.SERVICE_DISCOVERY_RESPONSE, AasdkOpenAutoProtocol.messageId(message))
        val descriptors = lengthDelimitedFields(AasdkOpenAutoProtocol.protobuf(message), 1)
        val inputDescriptor = descriptors.single {
            AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 1L
        }
        val inputChannel = lengthDelimitedFields(inputDescriptor, 4).single()
        val packedKeycodes = lengthDelimitedFields(inputChannel, 1).single()
        assertEquals(OpenAutoKeyCode.SUPPORTED.sorted(), packedVarints(packedKeycodes).map(Long::toInt))
    }

    @Test
    fun `closed resolution profiles advertise matching video and touch geometry`() {
        assertArrayEquals(
            AasdkOpenAutoProtocol.serviceDiscoveryResponse(),
            AasdkOpenAutoProtocol.serviceDiscoveryResponse(
                ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig(),
            ),
        )
        val expectedResolutionEnums = mapOf(
            ProjectionVideoProfile.FREE_800X480 to 1L,
            ProjectionVideoProfile.PREMIUM_720P to 2L,
            ProjectionVideoProfile.PREMIUM_1080P to 3L,
        )

        expectedResolutionEnums.forEach { (profile, resolutionEnum) ->
            val response = AasdkOpenAutoProtocol.serviceDiscoveryResponse(profile.toOpenAutoConfig())
            val descriptors = lengthDelimitedFields(AasdkOpenAutoProtocol.protobuf(response), 1)

            val videoDescriptor = descriptors.single {
                AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 3L
            }
            val videoChannel = lengthDelimitedFields(videoDescriptor, 3).single()
            val videoConfig = lengthDelimitedFields(videoChannel, 4).single()
            assertEquals(resolutionEnum, AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 1))
            assertNull(AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 3))
            assertNull(AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 4))
            assertEquals(
                profile.dpi.toLong(),
                AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 5),
            )

            val inputDescriptor = descriptors.single {
                AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 1L
            }
            val inputChannel = lengthDelimitedFields(inputDescriptor, 4).single()
            val touchConfig = lengthDelimitedFields(inputChannel, 2).single()
            assertEquals(
                profile.width.toLong(),
                AasdkOpenAutoProtocol.readFirstVarintField(touchConfig, 1),
            )
            assertEquals(
                profile.height.toLong(),
                AasdkOpenAutoProtocol.readFirstVarintField(touchConfig, 2),
            )
        }
    }

    @Test
    fun `portrait resolutions advertise matching enum and touch geometry`() {
        val expected = listOf(
            Triple(ProjectionVideoProfile.FREE_800X480, VideoViewport(720, 1280), 6L),
            Triple(ProjectionVideoProfile.PREMIUM_720P, VideoViewport(720, 1280), 6L),
            Triple(ProjectionVideoProfile.PREMIUM_1080P, VideoViewport(1080, 1920), 7L),
        )

        expected.forEach { (profile, viewport, resolutionEnum) ->
            val config = profile.toOpenAutoConfig(viewport)
            val response = AasdkOpenAutoProtocol.serviceDiscoveryResponse(
                config,
            )
            val descriptors = lengthDelimitedFields(AasdkOpenAutoProtocol.protobuf(response), 1)
            val videoDescriptor = descriptors.single {
                AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 3L
            }
            val videoChannel = lengthDelimitedFields(videoDescriptor, 3).single()
            val videoConfig = lengthDelimitedFields(videoChannel, 4).single()
            assertEquals(resolutionEnum, AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 1))
            assertEquals(
                config.dpi.toLong(),
                AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 5),
            )

            val inputDescriptor = descriptors.single {
                AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 1L
            }
            val inputChannel = lengthDelimitedFields(inputDescriptor, 4).single()
            val touchConfig = lengthDelimitedFields(inputChannel, 2).single()
            assertEquals(
                viewport.width.toLong(),
                AasdkOpenAutoProtocol.readFirstVarintField(touchConfig, 1),
            )
            assertEquals(
                viewport.height.toLong(),
                AasdkOpenAutoProtocol.readFirstVarintField(touchConfig, 2),
            )
        }
    }

    @Test
    fun `dynamic total margins rebuild nested lengths while touch keeps encoded geometry`() {
        val config = ProjectionVideoProfile.PREMIUM_720P.toOpenAutoConfig().copy(
            totalMarginWidth = 280,
            totalMarginHeight = 120,
        )

        val response = AasdkOpenAutoProtocol.serviceDiscoveryResponse(config)
        val descriptors = lengthDelimitedFields(AasdkOpenAutoProtocol.protobuf(response), 1)
        val videoDescriptor = descriptors.single {
            AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 3L
        }
        val videoChannel = lengthDelimitedFields(videoDescriptor, 3).single()
        val videoConfig = lengthDelimitedFields(videoChannel, 4).single()
        assertEquals(2L, AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 1))
        assertEquals(280L, AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 3))
        assertEquals(120L, AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 4))
        assertEquals(220L, AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 5))

        val inputDescriptor = descriptors.single {
            AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 1L
        }
        val inputChannel = lengthDelimitedFields(inputDescriptor, 4).single()
        val touchConfig = lengthDelimitedFields(inputChannel, 2).single()
        assertEquals(1280L, AasdkOpenAutoProtocol.readFirstVarintField(touchConfig, 1))
        assertEquals(720L, AasdkOpenAutoProtocol.readFirstVarintField(touchConfig, 2))
    }

    @Test
    fun `browser portrait uses portrait protocol frame with resolution aware density`() {
        val base = ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig()
        val portraitBase = ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig(
            ProjectionVideoProfile.FREE_800X480.portraitViewport,
        )
        val landscape = ProjectionViewportResolver.resolve(
            encodedViewport = base.viewport,
            browserWidth = 1920,
            browserHeight = 1080,
            baseDensityDpi = base.dpi,
            browserDevicePixelRatio = 1.0,
        ).applyTo(base)
        val portrait = ProjectionViewportResolver.resolve(
            encodedViewport = portraitBase.viewport,
            browserWidth = 576,
            browserHeight = 976,
            baseDensityDpi = portraitBase.dpi,
            browserDevicePixelRatio = 3.0,
        ).applyTo(portraitBase)
        val mobilePortrait = ProjectionViewportResolver.resolve(
            encodedViewport = portraitBase.viewport,
            browserWidth = 360,
            browserHeight = 800,
            baseDensityDpi = portraitBase.dpi,
            browserDevicePixelRatio = 3.0,
        ).applyTo(portraitBase)

        assertEquals(140, landscape.dpi)
        assertEquals(0, landscape.totalMarginWidth)
        assertEquals(32, landscape.totalMarginHeight)
        assertEquals(220, portrait.dpi)
        assertEquals(0, portrait.totalMarginWidth)
        assertEquals(64, portrait.totalMarginHeight)
        assertEquals(220, mobilePortrait.dpi)
        assertEquals(144, mobilePortrait.totalMarginWidth)
        assertEquals(0, mobilePortrait.totalMarginHeight)

        val portraitDescriptors = lengthDelimitedFields(
            AasdkOpenAutoProtocol.protobuf(
                AasdkOpenAutoProtocol.serviceDiscoveryResponse(portrait),
            ),
            1,
        )
        val portraitVideoDescriptor = portraitDescriptors.single {
            AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 3L
        }
        val portraitVideoChannel = lengthDelimitedFields(portraitVideoDescriptor, 3).single()
        val portraitVideoConfig = lengthDelimitedFields(portraitVideoChannel, 4).single()
        assertEquals(6L, AasdkOpenAutoProtocol.readFirstVarintField(portraitVideoConfig, 1))
        assertEquals(64L, AasdkOpenAutoProtocol.readFirstVarintField(portraitVideoConfig, 4))
        assertEquals(220L, AasdkOpenAutoProtocol.readFirstVarintField(portraitVideoConfig, 5))

        val portraitInputDescriptor = portraitDescriptors.single {
            AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 1L
        }
        val portraitInputChannel = lengthDelimitedFields(portraitInputDescriptor, 4).single()
        val portraitTouchConfig = lengthDelimitedFields(portraitInputChannel, 2).single()
        assertEquals(720L, AasdkOpenAutoProtocol.readFirstVarintField(portraitTouchConfig, 1))
        assertEquals(1280L, AasdkOpenAutoProtocol.readFirstVarintField(portraitTouchConfig, 2))

        val mobileDescriptors = lengthDelimitedFields(
            AasdkOpenAutoProtocol.protobuf(
                AasdkOpenAutoProtocol.serviceDiscoveryResponse(mobilePortrait),
            ),
            1,
        )
        val mobileVideoDescriptor = mobileDescriptors.single {
            AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 3L
        }
        val mobileVideoChannel = lengthDelimitedFields(mobileVideoDescriptor, 3).single()
        val mobileVideoConfig = lengthDelimitedFields(mobileVideoChannel, 4).single()
        assertEquals(144L, AasdkOpenAutoProtocol.readFirstVarintField(mobileVideoConfig, 3))
        assertEquals(220L, AasdkOpenAutoProtocol.readFirstVarintField(mobileVideoConfig, 5))
    }

    @Test
    fun `service discovery accepts app selected density and rejects unsafe density`() {
        val base = ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig()
        val response = AasdkOpenAutoProtocol.serviceDiscoveryResponse(base.copy(dpi = 78))
        val descriptors = lengthDelimitedFields(response.copyOfRange(4, response.size), 1)
        val videoDescriptor = descriptors.single {
            AasdkOpenAutoProtocol.readFirstVarintField(it, 1) == 3L
        }
        val videoChannel = lengthDelimitedFields(videoDescriptor, 3).single()
        val videoConfig = lengthDelimitedFields(videoChannel, 4).single()
        assertEquals(78L, AasdkOpenAutoProtocol.readFirstVarintField(videoConfig, 5))

        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.serviceDiscoveryResponse(base.copy(dpi = 68))
        }
        AasdkOpenAutoProtocol.serviceDiscoveryResponse(base.copy(dpi = 141))
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.serviceDiscoveryResponse(base.copy(dpi = 324))
        }
    }

    @Test
    fun `service discovery rejects arbitrary browser supplied geometry`() {
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.serviceDiscoveryResponse(
                OpenAutoConfig(
                    viewport = VideoViewport(1024, 600),
                    fps = 60,
                    dpi = 140,
                ),
            )
        }
    }

    @Test
    fun `proto2 success responses preserve explicit zero values`() {
        assertArrayEquals(
            bytes(0x00, 0x08, 0x08, 0x00),
            AasdkOpenAutoProtocol.channelOpenSuccess(),
        )
        assertArrayEquals(
            bytes(0x80, 0x02, 0x08, 0x00),
            AasdkOpenAutoProtocol.sensorStartSuccess(),
        )
        assertArrayEquals(
            bytes(0x80, 0x03, 0x08, 0x02, 0x10, 0x01, 0x18, 0x00),
            AasdkOpenAutoProtocol.avChannelSetupSuccess(),
        )
        assertArrayEquals(
            bytes(0x80, 0x03, 0x08, 0x01, 0x10, 0x01, 0x18, 0x00),
            AasdkOpenAutoProtocol.avChannelSetupFailure(),
        )
    }

    @Test
    fun `voice session notification parses pinned start and end status`() {
        assertEquals(0x0011, AasdkOpenAutoProtocol.VOICE_SESSION_REQUEST)
        assertEquals(
            AasdkVoiceSessionStatus.START,
            AasdkOpenAutoProtocol.parseVoiceSessionStatus(bytes(0x00, 0x11, 0x08, 0x01)),
        )
        assertEquals(
            AasdkVoiceSessionStatus.END,
            AasdkOpenAutoProtocol.parseVoiceSessionStatus(bytes(0x00, 0x11, 0x08, 0x02)),
        )
    }

    @Test
    fun `voice session notification rejects missing unknown and wrong message status`() {
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseVoiceSessionStatus(bytes(0x00, 0x11))
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseVoiceSessionStatus(bytes(0x00, 0x11, 0x08, 0x03))
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseVoiceSessionStatus(bytes(0x00, 0x12, 0x08, 0x01))
        }
    }

    @Test
    fun `AV input request parses pinned microphone flags and send window`() {
        assertEquals(
            AasdkAvInputOpenRequest(
                open = true,
                ancEnabled = true,
                echoCancellationEnabled = false,
                maxUnacked = 8,
            ),
            AasdkOpenAutoProtocol.parseAvInputOpenRequest(
                bytes(0x80, 0x05, 0x08, 0x01, 0x10, 0x01, 0x18, 0x00, 0x20, 0x08),
            ),
        )
        assertEquals(
            AasdkAvInputOpenRequest(
                open = false,
                ancEnabled = false,
                echoCancellationEnabled = false,
                maxUnacked = 0,
            ),
            AasdkOpenAutoProtocol.parseAvInputOpenRequest(bytes(0x80, 0x05)),
        )
    }

    @Test
    fun `AV input request rejects non boolean flags and unsafe send window`() {
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseAvInputOpenRequest(bytes(0x80, 0x05, 0x08, 0x02))
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseAvInputOpenRequest(
                bytes(0x80, 0x05, 0x20, 0x80, 0x80, 0x80, 0x80, 0x08),
            )
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseAvInputOpenRequest(bytes(0x80, 0x04, 0x08, 0x01))
        }
    }

    @Test
    fun `driving status unrestricted is guarded by bench decision`() {
        assertThrows(SecurityException::class.java) {
            AasdkOpenAutoProtocol.drivingStatusUnrestricted(benchSafetyConfirmed = false)
        }
        assertArrayEquals(
            bytes(0x80, 0x03, 0x6A, 0x02, 0x08, 0x00),
            AasdkOpenAutoProtocol.drivingStatusUnrestricted(benchSafetyConfirmed = true),
        )
    }

    @Test
    fun `audio focus follows pinned OpenAuto gain and release mapping`() {
        assertArrayEquals(
            bytes(0x00, 0x13, 0x08, 0x01),
            AasdkOpenAutoProtocol.audioFocusResponse(bytes(0x00, 0x12, 0x08, 0x01)),
        )
        assertArrayEquals(
            bytes(0x00, 0x13, 0x08, 0x03),
            AasdkOpenAutoProtocol.audioFocusResponse(bytes(0x00, 0x12, 0x08, 0x04)),
        )
        assertArrayEquals(
            bytes(0x00, 0x13, 0x08, 0x01),
            AasdkOpenAutoProtocol.audioFocusResponse(bytes(0x00, 0x12, 0x08, 0x03)),
        )
        assertArrayEquals(
            bytes(0x00, 0x13, 0x08, 0x01),
            AasdkOpenAutoProtocol.audioFocusResponse(bytes(0x00, 0x12)),
        )
    }

    @Test
    fun `media acknowledgement preserves required zero session`() {
        assertArrayEquals(
            bytes(0x80, 0x04, 0x08, 0x00, 0x10, 0x01),
            AasdkOpenAutoProtocol.avMediaAck(session = 0),
        )
        assertArrayEquals(
            bytes(0x80, 0x04, 0x08, 0xAC, 0x02, 0x10, 0x01),
            AasdkOpenAutoProtocol.avMediaAck(session = 300),
        )
    }

    @Test
    fun `microphone acknowledgement parses required session and unsigned value`() {
        assertEquals(
            AasdkAvMediaAck(session = 0, value = 1),
            AasdkOpenAutoProtocol.parseAvMediaAck(bytes(0x80, 0x04, 0x08, 0x00, 0x10, 0x01)),
        )
        assertEquals(
            AasdkAvMediaAck(session = 300, value = 0xFFFF_FFFFL),
            AasdkOpenAutoProtocol.parseAvMediaAck(
                bytes(0x80, 0x04, 0x08, 0xAC, 0x02, 0x10, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F),
            ),
        )
    }

    @Test
    fun `microphone acknowledgement rejects missing and out of range required fields`() {
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseAvMediaAck(bytes(0x80, 0x04, 0x08, 0x00))
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseAvMediaAck(
                bytes(0x80, 0x04, 0x08, 0x00, 0x10, 0x80, 0x80, 0x80, 0x80, 0x10),
            )
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseAvMediaAck(bytes(0x80, 0x05, 0x08, 0x00, 0x10, 0x01))
        }
    }

    @Test
    fun `timestamped microphone PCM preserves big endian timestamp and little endian samples`() {
        val pcm = bytes(0x34, 0x12, 0xCD, 0xAB)
        val encoded = AasdkOpenAutoProtocol.microphonePcmWithTimestamp(
            timestampMicros = 0x0102_0304_0506_0708L,
            pcm = pcm,
        )

        assertArrayEquals(
            bytes(
                0x00, 0x00,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x34, 0x12, 0xCD, 0xAB,
            ),
            encoded,
        )
        pcm[0] = 0
        assertEquals(0x34, encoded[10].toInt() and 0xFF)
    }

    @Test
    fun `timestamped microphone PCM rejects invalid timestamp and sample alignment`() {
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.microphonePcmWithTimestamp(-1L, bytes(0x00, 0x00))
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.microphonePcmWithTimestamp(0L, byteArrayOf())
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.microphonePcmWithTimestamp(0L, bytes(0x00))
        }
    }

    @Test
    fun `single pointer press preserves modern required zero fields`() {
        assertArrayEquals(
            bytes(
                0x80, 0x01,
                0x08, 0xC0, 0x84, 0x3D,
                0x1A, 0x0E,
                0x0A, 0x08,
                0x08, 0x9F, 0x06,
                0x10, 0xDF, 0x03,
                0x18, 0x00,
                0x10, 0x00,
                0x18, 0x00,
            ),
            AasdkOpenAutoProtocol.inputEventIndication(
                event = touch(TouchPhase.DOWN, x = 799, y = 479),
                timestampNanos = 1_000_000L,
            ),
        )
    }

    @Test
    fun `drag and release encode pinned action enum and pointer id`() {
        val drag = AasdkOpenAutoProtocol.inputEventIndication(
            event = touch(TouchPhase.MOVE, x = 1, y = 2, pointerId = 3),
            timestampNanos = 1L,
        )
        val release = AasdkOpenAutoProtocol.inputEventIndication(
            event = touch(TouchPhase.UP, x = 1, y = 2, pointerId = 3),
            timestampNanos = 1L,
        )
        val cancel = AasdkOpenAutoProtocol.inputEventIndication(
            event = touch(TouchPhase.CANCEL, x = 1, y = 2, pointerId = 3),
            timestampNanos = 1L,
        )

        assertArrayEquals(
            bytes(
                0x80, 0x01, 0x08, 0x01,
                0x1A, 0x0C, 0x0A, 0x06,
                0x08, 0x01, 0x10, 0x02, 0x18, 0x03,
                0x10, 0x00,
                0x18, 0x02,
            ),
            drag,
        )
        assertArrayEquals(
            bytes(
                0x80, 0x01, 0x08, 0x01,
                0x1A, 0x0C, 0x0A, 0x06,
                0x08, 0x01, 0x10, 0x02, 0x18, 0x03,
                0x10, 0x00,
                0x18, 0x01,
            ),
            release,
        )
        assertArrayEquals(
            bytes(
                0x80, 0x01, 0x08, 0x01,
                0x1A, 0x0C, 0x0A, 0x06,
                0x08, 0x01, 0x10, 0x02, 0x18, 0x03,
                0x10, 0x00,
                0x18, 0x03,
            ),
            cancel,
        )
    }

    @Test
    fun `touch encoder rejects fields that cannot be unsigned protobuf values`() {
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.inputEventIndication(
                event = touch(TouchPhase.DOWN, x = -1, y = 0),
                timestampNanos = 1L,
            )
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.inputEventIndication(
                event = touch(TouchPhase.DOWN, x = 0, y = 0),
                timestampNanos = -1L,
            )
        }
    }

    @Test
    fun `one queued button click expands to adjacent pressed and released wire events`() {
        val click = OpenAutoKeyEvent(OpenAutoKeyCode.BACK, OpenAutoKeyAction.CLICK)
        val sequence = AasdkOpenAutoProtocol.keyInputEventIndications(
            event = click,
            firstTimestampNanos = 1L,
            secondTimestampNanos = 2L,
        )
        assertEquals(2, sequence.size)
        assertArrayEquals(
            bytes(
                0x80, 0x01, 0x08, 0x01,
                0x22, 0x0A, 0x0A, 0x08,
                0x08, 0x04, 0x10, 0x01, 0x18, 0x00, 0x20, 0x00,
            ),
            sequence[0],
        )
        assertArrayEquals(
            bytes(
                0x80, 0x01, 0x08, 0x02,
                0x22, 0x0A, 0x0A, 0x08,
                0x08, 0x04, 0x10, 0x00, 0x18, 0x00, 0x20, 0x00,
            ),
            sequence[1],
        )
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.keyInputEventIndications(click, 2L, 2L)
        }
    }

    @Test
    fun `scroll wheel encodes signed relative delta`() {
        assertArrayEquals(
            bytes(
                0x80, 0x01, 0x08, 0x01,
                0x32, 0x11, 0x0A, 0x0F,
                0x08, 0x80, 0x80, 0x04,
                0x10, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                0xFF, 0xFF, 0xFF, 0xFF, 0x01,
            ),
            AasdkOpenAutoProtocol.relativeInputEventIndication(
                OpenAutoKeyEvent(OpenAutoKeyCode.SCROLL_WHEEL, OpenAutoKeyAction.SCROLL_LEFT),
                timestampNanos = 1L,
            ),
        )
        assertArrayEquals(
            bytes(
                0x80, 0x01, 0x08, 0x01,
                0x32, 0x08, 0x0A, 0x06,
                0x08, 0x80, 0x80, 0x04, 0x10, 0x01,
            ),
            AasdkOpenAutoProtocol.relativeInputEventIndication(
                OpenAutoKeyEvent(OpenAutoKeyCode.SCROLL_WHEEL, OpenAutoKeyAction.SCROLL_RIGHT),
                timestampNanos = 1L,
            ),
        )
    }

    @Test
    fun `key encoder rejects unsupported codes actions and timestamp`() {
        listOf(
            OpenAutoKeyEvent(0, OpenAutoKeyAction.CLICK),
            OpenAutoKeyEvent(OpenAutoKeyCode.SCROLL_WHEEL, OpenAutoKeyAction.CLICK),
            OpenAutoKeyEvent(OpenAutoKeyCode.BACK, OpenAutoKeyAction.SCROLL_LEFT),
        ).forEach { event ->
            assertThrows(AasdkProtocolException::class.java) {
                if (event.action == OpenAutoKeyAction.CLICK) {
                    AasdkOpenAutoProtocol.buttonInputEventIndication(
                        event = event,
                        isPressed = true,
                        timestampNanos = 1L,
                    )
                } else {
                    AasdkOpenAutoProtocol.relativeInputEventIndication(event, timestampNanos = 1L)
                }
            }
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.buttonInputEventIndication(
                event = OpenAutoKeyEvent(OpenAutoKeyCode.BACK, OpenAutoKeyAction.CLICK),
                isPressed = true,
                timestampNanos = -1L,
            )
        }
    }

    @Test
    fun `binding request accepts packed and unpacked allowlisted scan codes`() {
        val packed = bytes(0x80, 0x02, 0x0A, 0x05, 0x04, 0x17, 0x80, 0x80, 0x04)
        val unpacked = bytes(0x80, 0x02, 0x08, 0x04, 0x08, 0x17)

        assertEquals(
            listOf(OpenAutoKeyCode.BACK, OpenAutoKeyCode.ENTER, OpenAutoKeyCode.SCROLL_WHEEL),
            AasdkOpenAutoProtocol.parseBindingRequestScanCodes(packed),
        )
        assertEquals(
            listOf(OpenAutoKeyCode.BACK, OpenAutoKeyCode.ENTER),
            AasdkOpenAutoProtocol.parseBindingRequestScanCodes(unpacked),
        )
        assertEquals(true, AasdkOpenAutoProtocol.bindingRequestSupported(packed))
        assertEquals(true, AasdkOpenAutoProtocol.bindingRequestSupported(bytes(0x80, 0x02)))
    }

    @Test
    fun `binding request fails closed for unknown negative and malformed codes`() {
        assertEquals(
            false,
            AasdkOpenAutoProtocol.bindingRequestSupported(bytes(0x80, 0x02, 0x08, 0x00)),
        )
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseBindingRequestScanCodes(
                bytes(
                    0x80, 0x02, 0x08,
                    0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                    0xFF, 0xFF, 0xFF, 0xFF, 0x01,
                ),
            )
        }
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.parseBindingRequestScanCodes(
                bytes(0x80, 0x02, 0x0A, 0x02, 0x80),
            )
        }
    }

    @Test
    fun `ping includes the timestamp required by modern Android Auto`() {
        assertArrayEquals(
            bytes(0x00, 0x0B, 0x08, 0xAC, 0x02),
            AasdkOpenAutoProtocol.pingRequest(timestampMicros = 300),
        )
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.pingRequest(timestampMicros = -1)
        }
    }

    @Test
    fun `bounded protobuf reader skips known wire types and rejects truncation`() {
        val fixture = bytes(
            0x12, 0x02, 0xAA, 0xBB,
            0x08, 0x8D, 0x01,
        )
        assertEquals(141L, AasdkOpenAutoProtocol.readFirstVarintField(fixture, 1))
        assertNull(AasdkOpenAutoProtocol.readFirstVarintField(fixture, 3))
        assertThrows(AasdkProtocolException::class.java) {
            AasdkOpenAutoProtocol.readFirstVarintField(bytes(0x12, 0x04, 0x01), 1)
        }
    }

    private fun touch(
        phase: TouchPhase,
        x: Int,
        y: Int,
        pointerId: Int = 0,
    ): OpenAutoTouchEvent = OpenAutoTouchEvent(
        phase = phase,
        x = x,
        y = y,
        pointerId = pointerId,
        timestampNanos = 1_000L,
    )

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun lengthDelimitedFields(protobuf: ByteArray, targetField: Int): List<ByteArray> {
        val output = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < protobuf.size) {
            val key = readVarint(protobuf, offset)
            offset = key.second
            val fieldNumber = (key.first ushr 3).toInt()
            when ((key.first and 0x07).toInt()) {
                0 -> offset = readVarint(protobuf, offset).second
                2 -> {
                    val length = readVarint(protobuf, offset)
                    offset = length.second
                    val end = offset + length.first.toInt()
                    require(end in offset..protobuf.size)
                    if (fieldNumber == targetField) output += protobuf.copyOfRange(offset, end)
                    offset = end
                }
                else -> error("unsupported test fixture wire type")
            }
        }
        return output
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var offset = start
        while (offset < data.size && shift < 64) {
            val byte = data[offset].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            offset += 1
            if (byte and 0x80 == 0) return value to offset
            shift += 7
        }
        error("invalid test fixture varint")
    }

    private fun packedVarints(data: ByteArray): List<Long> {
        val output = mutableListOf<Long>()
        var offset = 0
        while (offset < data.size) {
            val value = readVarint(data, offset)
            output += value.first
            offset = value.second
        }
        return output
    }
}
