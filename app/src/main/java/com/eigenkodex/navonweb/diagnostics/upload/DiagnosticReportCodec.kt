/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object DiagnosticReportCodec {
    fun encode(report: DiagnosticReport): ByteArray {
        validate(report, requireExplicitConsent = true)
        val descriptionBytes = report.userDescription.toByteArray(StandardCharsets.UTF_8)
        val logBytes = report.snapshot.logText.toByteArray(StandardCharsets.UTF_8)
        val localeBytes = report.clientLocale.toByteArray(StandardCharsets.UTF_8)
        return ByteArrayOutputStream(
            HEADER_BYTES + descriptionBytes.size + logBytes.size + localeBytes.size,
        ).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeLong(report.id.mostSignificantBits)
                output.writeLong(report.id.leastSignificantBits)
                output.writeLong(report.occurredAtEpochMillis)
                output.writeInt(report.userConsentVersion)
                output.writeLong(requireNotNull(report.userApprovedAtEpochMillis))
                output.writeBoolean(report.snapshot.truncated)
                output.writeLong(report.snapshot.originalByteCount)
                output.writeInt(descriptionBytes.size)
                output.writeInt(logBytes.size)
                output.writeInt(localeBytes.size)
                output.write(descriptionBytes)
                output.write(logBytes)
                output.write(localeBytes)
            }
            bytes.toByteArray()
        }
    }

    fun decode(encoded: ByteArray): DiagnosticReport {
        require(encoded.size <= MAX_ENCODED_BYTES) { "encoded report exceeds maximum" }
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "invalid report header" }
            val version = input.readInt()
            require(version in LEGACY_FORMAT_VERSION..FORMAT_VERSION) { "unsupported report version" }
            val id = UUID(input.readLong(), input.readLong())
            val occurredAtEpochMillis = input.readLong()
            val consentVersion = if (version >= EXPLICIT_CONSENT_FORMAT_VERSION) {
                input.readInt()
            } else {
                0
            }
            val approvedAtEpochMillis = if (version >= EXPLICIT_CONSENT_FORMAT_VERSION) {
                input.readLong()
            } else {
                null
            }
            val truncated = input.readBoolean()
            val originalByteCount = input.readLong()
            val descriptionLength = readBoundedLength(
                input,
                DiagnosticUploadPolicy.MAX_DESCRIPTION_UTF8_BYTES,
            )
            val logLength = readBoundedLength(input, DiagnosticUploadPolicy.MAX_LOG_BYTES)
            val localeLength = if (version >= LOCALE_FORMAT_VERSION) {
                readBoundedLength(input, DiagnosticClientLocale.MAX_UTF8_BYTES)
            } else {
                0
            }
            val descriptionBytes = ByteArray(descriptionLength).also(input::readFully)
            val logBytes = ByteArray(logLength).also(input::readFully)
            val localeBytes = ByteArray(localeLength).also(input::readFully)
            require(input.read() == -1) { "trailing report bytes" }
            val report = DiagnosticReport(
                id = id,
                occurredAtEpochMillis = occurredAtEpochMillis,
                userConsentVersion = consentVersion,
                userApprovedAtEpochMillis = approvedAtEpochMillis,
                userDescription = descriptionBytes.toString(StandardCharsets.UTF_8),
                snapshot = DiagnosticLogSnapshot(
                    logText = logBytes.toString(StandardCharsets.UTF_8),
                    truncated = truncated,
                    originalByteCount = originalByteCount,
                ),
                clientLocale = if (version >= LOCALE_FORMAT_VERSION) {
                    localeBytes.toString(StandardCharsets.UTF_8)
                } else {
                    DiagnosticClientLocale.UNKNOWN
                },
            )
            validate(
                report,
                requireExplicitConsent = version >= EXPLICIT_CONSENT_FORMAT_VERSION,
            )
            report
        }
    }

    private fun readBoundedLength(input: DataInputStream, maximum: Int): Int {
        val length = input.readInt()
        require(length in 0..maximum) { "invalid field length" }
        return length
    }

    private fun validate(report: DiagnosticReport, requireExplicitConsent: Boolean) {
        require(report.occurredAtEpochMillis > 0L)
        if (requireExplicitConsent) {
            require(report.hasExplicitUploadConsent) { "explicit upload consent is required" }
        } else {
            require(report.userConsentVersion == 0 && report.userApprovedAtEpochMillis == null) {
                "legacy report has an invalid consent marker"
            }
        }
        val description = DiagnosticUploadPolicy.normalizeDescription(report.userDescription)
        require(description is DescriptionValidation.Valid && description.value == report.userDescription)
        require(DiagnosticClientLocale.normalize(report.clientLocale) == report.clientLocale) {
            "invalid client locale"
        }
        val snapshot = report.snapshot
        val selectedBytes = snapshot.selectedByteCount
        require(selectedBytes in 1..DiagnosticUploadPolicy.MAX_LOG_BYTES)
        require(snapshot.logText.endsWith('\n'))
        require(snapshot.originalByteCount >= selectedBytes.toLong())
        require(snapshot.truncated == (snapshot.originalByteCount > selectedBytes.toLong()))
    }

    private const val HEADER_BYTES = 128
    internal const val MAX_ENCODED_BYTES: Int =
        DiagnosticUploadPolicy.MAX_LOG_BYTES +
            DiagnosticUploadPolicy.MAX_DESCRIPTION_UTF8_BYTES +
            DiagnosticClientLocale.MAX_UTF8_BYTES +
            HEADER_BYTES
    private const val LEGACY_FORMAT_VERSION = 1
    private const val LOCALE_FORMAT_VERSION = 2
    private const val EXPLICIT_CONSENT_FORMAT_VERSION = 3
    private const val FORMAT_VERSION = EXPLICIT_CONSENT_FORMAT_VERSION
    private val MAGIC = "NVDIAG01".toByteArray(StandardCharsets.US_ASCII)
}
