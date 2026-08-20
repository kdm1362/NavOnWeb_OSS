/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportCodecTest {
    @Test
    fun roundTripPreservesTheStableReportIdAndUploadFields() {
        val report = DiagnosticReport(
            id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            occurredAtEpochMillis = 1_754_083_200_000L,
            userConsentVersion = DIAGNOSTIC_UPLOAD_CONSENT_VERSION,
            userApprovedAtEpochMillis = 1_754_083_199_000L,
            userDescription = "음성비서를 부른 직후 연결이 종료됨",
            snapshot = DiagnosticLogSnapshot(
                logText = "{\"event\":\"SESSION_FAILED\"}\n",
                truncated = true,
                originalByteCount = 1_024L,
            ),
            clientLocale = "ko-KR",
        )

        val decoded = DiagnosticReportCodec.decode(DiagnosticReportCodec.encode(report))

        assertEquals(report, decoded)
        assertTrue(decoded.hasExplicitUploadConsent)
    }

    @Test
    fun legacyV1OutboxReportRemainsReadableWithUnknownLocale() {
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")
        val description = "legacy"
        val log = "{}\n"
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NVDIAG01".toByteArray(StandardCharsets.US_ASCII))
                output.writeInt(1)
                output.writeLong(id.mostSignificantBits)
                output.writeLong(id.leastSignificantBits)
                output.writeLong(1_754_083_200_000L)
                output.writeBoolean(false)
                output.writeLong(log.toByteArray(StandardCharsets.UTF_8).size.toLong())
                output.writeInt(description.toByteArray(StandardCharsets.UTF_8).size)
                output.writeInt(log.toByteArray(StandardCharsets.UTF_8).size)
                output.write(description.toByteArray(StandardCharsets.UTF_8))
                output.write(log.toByteArray(StandardCharsets.UTF_8))
            }
            bytes.toByteArray()
        }

        val decoded = DiagnosticReportCodec.decode(encoded)

        assertEquals(id, decoded.id)
        assertEquals(DiagnosticClientLocale.UNKNOWN, decoded.clientLocale)
        assertEquals(description, decoded.userDescription)
        assertEquals(0, decoded.userConsentVersion)
        assertEquals(null, decoded.userApprovedAtEpochMillis)
        assertFalse(decoded.hasExplicitUploadConsent)
    }

    @Test
    fun legacyV2OutboxReportWithLocaleHasNoExplicitConsentAndCannotBeResumed() {
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174002")
        val occurredAt = 1_754_083_200_000L
        val description = "legacy with locale"
        val log = "{}\n"
        val locale = "ko-KR"
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write("NVDIAG01".toByteArray(StandardCharsets.US_ASCII))
                output.writeInt(2)
                output.writeLong(id.mostSignificantBits)
                output.writeLong(id.leastSignificantBits)
                output.writeLong(occurredAt)
                output.writeBoolean(false)
                output.writeLong(log.toByteArray(StandardCharsets.UTF_8).size.toLong())
                output.writeInt(description.toByteArray(StandardCharsets.UTF_8).size)
                output.writeInt(log.toByteArray(StandardCharsets.UTF_8).size)
                output.writeInt(locale.toByteArray(StandardCharsets.UTF_8).size)
                output.write(description.toByteArray(StandardCharsets.UTF_8))
                output.write(log.toByteArray(StandardCharsets.UTF_8))
                output.write(locale.toByteArray(StandardCharsets.UTF_8))
            }
            bytes.toByteArray()
        }

        val decoded = DiagnosticReportCodec.decode(encoded)

        assertEquals(locale, decoded.clientLocale)
        assertEquals(0, decoded.userConsentVersion)
        assertEquals(null, decoded.userApprovedAtEpochMillis)
        assertFalse(decoded.hasExplicitUploadConsent)
    }

    @Test
    fun rejectsTrailingOrMalformedOutboxData() {
        val report = DiagnosticReport(
            id = UUID.randomUUID(),
            occurredAtEpochMillis = 1L,
            userConsentVersion = DIAGNOSTIC_UPLOAD_CONSENT_VERSION,
            userApprovedAtEpochMillis = 1L,
            userDescription = "설명",
            snapshot = DiagnosticLogSnapshot(
                logText = "{}\n",
                truncated = false,
                originalByteCount = 3L,
            ),
        )
        val encoded = DiagnosticReportCodec.encode(report)

        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticReportCodec.decode(encoded + byteArrayOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticReportCodec.decode(encoded.copyOfRange(1, encoded.size))
        }
    }

    @Test
    fun refusesLogsThatAreNotCompleteJsonlRecords() {
        val report = DiagnosticReport(
            id = UUID.randomUUID(),
            occurredAtEpochMillis = 1L,
            userConsentVersion = DIAGNOSTIC_UPLOAD_CONSENT_VERSION,
            userApprovedAtEpochMillis = 1L,
            userDescription = "설명",
            snapshot = DiagnosticLogSnapshot(
                logText = "{}",
                truncated = false,
                originalByteCount = 2L,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticReportCodec.encode(report)
        }
    }

    @Test
    fun refusesToPersistAReportWithoutExplicitUploadConsent() {
        val report = DiagnosticReport(
            id = UUID.randomUUID(),
            occurredAtEpochMillis = 1L,
            userConsentVersion = 0,
            userApprovedAtEpochMillis = null,
            userDescription = "설명",
            snapshot = DiagnosticLogSnapshot(
                logText = "{}\n",
                truncated = false,
                originalByteCount = 3L,
            ),
        )

        assertFalse(report.hasExplicitUploadConsent)
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticReportCodec.encode(report)
        }
    }
}
