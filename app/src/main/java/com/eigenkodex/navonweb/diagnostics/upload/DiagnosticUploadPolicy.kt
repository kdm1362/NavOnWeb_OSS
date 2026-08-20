/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration

internal object DiagnosticUploadPolicy {
    const val MAX_LOG_BYTES: Int = 256 * 1_024
    const val MAX_DESCRIPTION_CODE_POINTS: Int = 500
    const val MAX_DESCRIPTION_UTF8_BYTES: Int = 2 * 1_024
    const val MAX_OUTBOX_REPORTS: Int = 3
    const val MAX_ATTEMPTS: Int = 3
    val USER_APPROVED_SUBMISSION_RATE_LIMIT: Duration = Duration.ofMinutes(30)
    val RETRY_BACKOFF: Duration = Duration.ofMinutes(30)
    val MAX_REPORT_AGE: Duration = Duration.ofHours(24)

    fun normalizeDescription(value: String): DescriptionValidation {
        val normalized = Normalizer.normalize(
            value.replace("\r\n", "\n").replace('\r', '\n'),
            Normalizer.Form.NFC,
        ).trim()
        if (normalized.any(::isDisallowedControlCharacter)) {
            return DescriptionValidation.Invalid(DescriptionFailure.DISALLOWED_CONTROL_CHARACTER)
        }
        if (normalized.isEmpty()) {
            return DescriptionValidation.Invalid(DescriptionFailure.EMPTY)
        }
        if (normalized.codePointCount(0, normalized.length) > MAX_DESCRIPTION_CODE_POINTS) {
            return DescriptionValidation.Invalid(DescriptionFailure.TOO_MANY_CODE_POINTS)
        }
        if (normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_DESCRIPTION_UTF8_BYTES) {
            return DescriptionValidation.Invalid(DescriptionFailure.TOO_MANY_UTF8_BYTES)
        }
        return DescriptionValidation.Valid(normalized)
    }

    fun classifyHttpStatus(statusCode: Int): UploadDisposition = when {
        statusCode in 200..299 -> UploadDisposition.SUCCESS
        statusCode == 409 -> UploadDisposition.SUCCESS
        statusCode == 408 || statusCode == 429 || statusCode in 500..599 -> {
            UploadDisposition.RETRYABLE_FAILURE
        }
        else -> UploadDisposition.PERMANENT_FAILURE
    }

    fun shouldAttempt(runAttemptCount: Int, age: Duration): Boolean =
        runAttemptCount in 0 until MAX_ATTEMPTS && age < MAX_REPORT_AGE && !age.isNegative

    fun shouldRetryAfterFailure(attempt: Int, age: Duration): Boolean =
        attempt in 1 until MAX_ATTEMPTS && age < MAX_REPORT_AGE && !age.isNegative

    fun remainingReportLifetime(
        occurredAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Duration {
        if (occurredAtEpochMillis <= 0L) return Duration.ZERO
        if (nowEpochMillis <= occurredAtEpochMillis) return MAX_REPORT_AGE
        val ageMillis = runCatching {
            Math.subtractExact(nowEpochMillis, occurredAtEpochMillis)
        }.getOrDefault(Long.MAX_VALUE)
        val age = Duration.ofMillis(ageMillis)
        return if (age >= MAX_REPORT_AGE) Duration.ZERO else MAX_REPORT_AGE.minus(age)
    }

    /**
     * Local guard matching the server's owner rate limit. A backwards clock fails closed for one
     * full window; retry backoff is separate and applies only to an already-approved report.
     */
    fun userApprovalRateLimitRemaining(
        lastApprovedAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): Duration? {
        val lastApprovedAt = lastApprovedAtEpochMillis?.takeIf { it > 0L } ?: return null
        require(nowEpochMillis >= lastApprovedAt) {
            "user approval clock must be persistently reconciled before rate-limit evaluation"
        }
        val elapsed = Duration.ofMillis(nowEpochMillis - lastApprovedAt)
        if (elapsed >= USER_APPROVED_SUBMISSION_RATE_LIMIT) return null
        return USER_APPROVED_SUBMISSION_RATE_LIMIT.minus(elapsed)
    }

    /**
     * Reconciles the preference journal with an approved outbox report. Future wall-clock values
     * are persistently rebased once, so a clock rollback blocks for at most one fresh window.
     */
    fun reconcileUserApprovalClock(
        storedApprovedAtEpochMillis: Long?,
        pendingApprovedAtEpochMillis: Long?,
        storedFutureEvidenceEpochMillis: Long?,
        storedFutureEvidenceRebasedAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): UserApprovalClockReconciliation {
        val now = nowEpochMillis.coerceAtLeast(1L)
        val stored = storedApprovedAtEpochMillis?.takeIf { it > 0L }
        val pending = pendingApprovedAtEpochMillis?.takeIf { it > 0L }
        val trackedPendingEvidenceStillPresent =
            pending != null &&
                pending == storedFutureEvidenceEpochMillis &&
                storedFutureEvidenceRebasedAtEpochMillis != null
        if (trackedPendingEvidenceStillPresent) {
            val rebasedAt = storedFutureEvidenceRebasedAtEpochMillis.coerceIn(1L, now)
            return UserApprovalClockReconciliation(
                effectiveApprovedAtEpochMillis = maxOf(
                    stored?.takeIf { it <= now } ?: 0L,
                    rebasedAt,
                ),
                futureEvidenceEpochMillis = pending,
                futureEvidenceRebasedAtEpochMillis = rebasedAt,
            )
        }
        val rawLatest = listOfNotNull(stored, pending).maxOrNull()
            ?: return UserApprovalClockReconciliation.EMPTY
        if (rawLatest <= now) {
            return UserApprovalClockReconciliation(
                effectiveApprovedAtEpochMillis = rawLatest,
                futureEvidenceEpochMillis = null,
                futureEvidenceRebasedAtEpochMillis = null,
            )
        }

        val existingRebase = storedFutureEvidenceRebasedAtEpochMillis
            ?.takeIf {
                storedFutureEvidenceEpochMillis == rawLatest && it in 1L..now
            }
        val rebasedAt = existingRebase ?: now
        val effectiveApprovedAt = maxOf(stored?.takeIf { it <= now } ?: 0L, rebasedAt)
        return UserApprovalClockReconciliation(
            effectiveApprovedAtEpochMillis = effectiveApprovedAt,
            futureEvidenceEpochMillis = rawLatest,
            futureEvidenceRebasedAtEpochMillis = rebasedAt,
        )
    }

    private fun isDisallowedControlCharacter(character: Char): Boolean =
        character.code < 0x20 && character != '\n' && character != '\t'
}

internal data class UserApprovalClockReconciliation(
    val effectiveApprovedAtEpochMillis: Long?,
    val futureEvidenceEpochMillis: Long?,
    val futureEvidenceRebasedAtEpochMillis: Long?,
) {
    companion object {
        val EMPTY = UserApprovalClockReconciliation(null, null, null)
    }
}

internal sealed interface DescriptionValidation {
    data class Valid(val value: String) : DescriptionValidation

    data class Invalid(val reason: DescriptionFailure) : DescriptionValidation
}

internal enum class DescriptionFailure {
    EMPTY,
    TOO_MANY_CODE_POINTS,
    TOO_MANY_UTF8_BYTES,
    DISALLOWED_CONTROL_CHARACTER,
}

internal enum class UploadDisposition {
    SUCCESS,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}
