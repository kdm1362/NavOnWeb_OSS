/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploadPolicyTest {
    @Test
    fun descriptionUsesNormalizedCodePointsAndAllowsKorean() {
        val validation = DiagnosticUploadPolicy.normalizeDescription("  가 상황 설명  ")

        assertEquals(DescriptionValidation.Valid("가 상황 설명"), validation)
        val largestFourByteDescription = "😀".repeat(500)
        assertEquals(
            DescriptionValidation.Valid(largestFourByteDescription),
            DiagnosticUploadPolicy.normalizeDescription(largestFourByteDescription),
        )
        assertEquals(
            DescriptionValidation.Invalid(DescriptionFailure.TOO_MANY_CODE_POINTS),
            DiagnosticUploadPolicy.normalizeDescription("가".repeat(501)),
        )
    }

    @Test
    fun descriptionRejectsDisallowedControlCharacters() {
        assertEquals(
            DescriptionValidation.Invalid(DescriptionFailure.EMPTY),
            DiagnosticUploadPolicy.normalizeDescription("  \r\n  "),
        )
        assertEquals(
            DescriptionValidation.Invalid(DescriptionFailure.DISALLOWED_CONTROL_CHARACTER),
            DiagnosticUploadPolicy.normalizeDescription("상황\u0000설명"),
        )
        assertEquals(
            DescriptionValidation.Valid("첫 줄\n둘째 줄\t추가"),
            DiagnosticUploadPolicy.normalizeDescription("첫 줄\r\n둘째 줄\t추가"),
        )
    }

    @Test
    fun onlyTransientHttpStatusesRetryAndConflictIsIdempotentSuccess() {
        listOf(200, 201, 204, 409).forEach { status ->
            assertEquals(UploadDisposition.SUCCESS, DiagnosticUploadPolicy.classifyHttpStatus(status))
        }
        listOf(408, 429, 500, 503, 599).forEach { status ->
            assertEquals(
                UploadDisposition.RETRYABLE_FAILURE,
                DiagnosticUploadPolicy.classifyHttpStatus(status),
            )
        }
        listOf(400, 401, 403, 404, 413, 422).forEach { status ->
            assertEquals(
                UploadDisposition.PERMANENT_FAILURE,
                DiagnosticUploadPolicy.classifyHttpStatus(status),
            )
        }
    }

    @Test
    fun attemptsStopAfterThreeOrTwentyFourHours() {
        assertTrue(DiagnosticUploadPolicy.shouldAttempt(0, Duration.ZERO))
        assertTrue(DiagnosticUploadPolicy.shouldAttempt(2, Duration.ofHours(23)))
        assertFalse(DiagnosticUploadPolicy.shouldAttempt(3, Duration.ZERO))
        assertFalse(DiagnosticUploadPolicy.shouldAttempt(0, Duration.ofHours(24)))

        assertTrue(DiagnosticUploadPolicy.shouldRetryAfterFailure(1, Duration.ZERO))
        assertTrue(DiagnosticUploadPolicy.shouldRetryAfterFailure(2, Duration.ofHours(23)))
        assertFalse(DiagnosticUploadPolicy.shouldRetryAfterFailure(3, Duration.ZERO))
        assertFalse(DiagnosticUploadPolicy.shouldRetryAfterFailure(1, Duration.ofHours(24)))
        assertEquals(Duration.ofMinutes(30), DiagnosticUploadPolicy.RETRY_BACKOFF)
    }

    @Test
    fun recoveryPreservesOnlyTheRemainingReportLifetime() {
        val now = Duration.ofDays(100).toMillis()

        assertEquals(
            Duration.ofHours(1),
            DiagnosticUploadPolicy.remainingReportLifetime(
                occurredAtEpochMillis = now - Duration.ofHours(23).toMillis(),
                nowEpochMillis = now,
            ),
        )
        assertEquals(
            Duration.ZERO,
            DiagnosticUploadPolicy.remainingReportLifetime(
                occurredAtEpochMillis = now - Duration.ofHours(24).toMillis(),
                nowEpochMillis = now,
            ),
        )
        assertEquals(
            DiagnosticUploadPolicy.MAX_REPORT_AGE,
            DiagnosticUploadPolicy.remainingReportLifetime(
                occurredAtEpochMillis = now + 1L,
                nowEpochMillis = now,
            ),
        )
        assertEquals(
            Duration.ZERO,
            DiagnosticUploadPolicy.remainingReportLifetime(
                occurredAtEpochMillis = 0L,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun newUserApprovedSubmissionsAreRateLimitedForThirtyMinutes() {
        val approvedAt = 1_000_000L

        assertEquals(
            null,
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(null, approvedAt),
        )
        assertEquals(
            Duration.ofMinutes(30),
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(approvedAt, approvedAt),
        )
        assertEquals(
            Duration.ofMinutes(1),
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                approvedAt,
                approvedAt + Duration.ofMinutes(29).toMillis(),
            ),
        )
        assertEquals(
            null,
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                approvedAt,
                approvedAt + Duration.ofMinutes(30).toMillis(),
            ),
        )
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                approvedAt,
                approvedAt - 1L,
            )
        }
        assertEquals(
            Duration.ofMinutes(30),
            DiagnosticUploadPolicy.USER_APPROVED_SUBMISSION_RATE_LIMIT,
        )
    }

    @Test
    fun pendingOutboxApprovalRepairsTheCrashWindowBeforeRateLimitEvaluation() {
        val approvedAt = 1_000_000L
        val now = approvedAt + Duration.ofMinutes(1).toMillis()

        val reconciled = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = null,
            pendingApprovedAtEpochMillis = approvedAt,
            storedFutureEvidenceEpochMillis = null,
            storedFutureEvidenceRebasedAtEpochMillis = null,
            nowEpochMillis = now,
        )

        assertEquals(approvedAt, reconciled.effectiveApprovedAtEpochMillis)
        assertEquals(
            Duration.ofMinutes(29),
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                reconciled.effectiveApprovedAtEpochMillis,
                now,
            ),
        )
    }

    @Test
    fun futureApprovalEvidenceIsPersistentlyRebasedOnlyOnce() {
        val firstObservedNow = 1_000_000L
        val futurePendingApproval = firstObservedNow + Duration.ofDays(1).toMillis()
        val first = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = null,
            pendingApprovedAtEpochMillis = futurePendingApproval,
            storedFutureEvidenceEpochMillis = null,
            storedFutureEvidenceRebasedAtEpochMillis = null,
            nowEpochMillis = firstObservedNow,
        )
        assertEquals(firstObservedNow, first.effectiveApprovedAtEpochMillis)

        val fiveMinutesLater = firstObservedNow + Duration.ofMinutes(5).toMillis()
        val second = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = first.effectiveApprovedAtEpochMillis,
            pendingApprovedAtEpochMillis = futurePendingApproval,
            storedFutureEvidenceEpochMillis = first.futureEvidenceEpochMillis,
            storedFutureEvidenceRebasedAtEpochMillis = first.futureEvidenceRebasedAtEpochMillis,
            nowEpochMillis = fiveMinutesLater,
        )
        assertEquals(firstObservedNow, second.effectiveApprovedAtEpochMillis)
        assertEquals(
            Duration.ofMinutes(25),
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                second.effectiveApprovedAtEpochMillis,
                fiveMinutesLater,
            ),
        )

        val thirtyMinutesLater = firstObservedNow + Duration.ofMinutes(30).toMillis()
        val third = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = second.effectiveApprovedAtEpochMillis,
            pendingApprovedAtEpochMillis = futurePendingApproval,
            storedFutureEvidenceEpochMillis = second.futureEvidenceEpochMillis,
            storedFutureEvidenceRebasedAtEpochMillis = second.futureEvidenceRebasedAtEpochMillis,
            nowEpochMillis = thirtyMinutesLater,
        )
        assertEquals(firstObservedNow, third.effectiveApprovedAtEpochMillis)
        assertEquals(
            null,
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                third.effectiveApprovedAtEpochMillis,
                thirtyMinutesLater,
            ),
        )

        val afterNewApproval = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = thirtyMinutesLater,
            pendingApprovedAtEpochMillis = futurePendingApproval,
            storedFutureEvidenceEpochMillis = third.futureEvidenceEpochMillis,
            storedFutureEvidenceRebasedAtEpochMillis = third.futureEvidenceRebasedAtEpochMillis,
            nowEpochMillis = thirtyMinutesLater + Duration.ofMinutes(5).toMillis(),
        )
        assertEquals(thirtyMinutesLater, afterNewApproval.effectiveApprovedAtEpochMillis)
        assertEquals(
            Duration.ofMinutes(25),
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                afterNewApproval.effectiveApprovedAtEpochMillis,
                thirtyMinutesLater + Duration.ofMinutes(5).toMillis(),
            ),
        )
    }

    @Test
    fun trackedFutureEvidenceDoesNotRestartRateLimitAfterWallClockCatchesUp() {
        val firstObservedNow = 1_000_000L
        val futurePendingApproval = firstObservedNow + Duration.ofHours(1).toMillis()
        val first = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = null,
            pendingApprovedAtEpochMillis = futurePendingApproval,
            storedFutureEvidenceEpochMillis = null,
            storedFutureEvidenceRebasedAtEpochMillis = null,
            nowEpochMillis = firstObservedNow,
        )

        val afterOriginalFutureTimestamp = futurePendingApproval + Duration.ofMinutes(5).toMillis()
        val whilePending = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = first.effectiveApprovedAtEpochMillis,
            pendingApprovedAtEpochMillis = futurePendingApproval,
            storedFutureEvidenceEpochMillis = first.futureEvidenceEpochMillis,
            storedFutureEvidenceRebasedAtEpochMillis = first.futureEvidenceRebasedAtEpochMillis,
            nowEpochMillis = afterOriginalFutureTimestamp,
        )

        assertEquals(firstObservedNow, whilePending.effectiveApprovedAtEpochMillis)
        assertEquals(futurePendingApproval, whilePending.futureEvidenceEpochMillis)
        assertEquals(firstObservedNow, whilePending.futureEvidenceRebasedAtEpochMillis)
        assertEquals(
            null,
            DiagnosticUploadPolicy.userApprovalRateLimitRemaining(
                whilePending.effectiveApprovedAtEpochMillis,
                afterOriginalFutureTimestamp,
            ),
        )

        val afterPendingRemoval = DiagnosticUploadPolicy.reconcileUserApprovalClock(
            storedApprovedAtEpochMillis = whilePending.effectiveApprovedAtEpochMillis,
            pendingApprovedAtEpochMillis = null,
            storedFutureEvidenceEpochMillis = whilePending.futureEvidenceEpochMillis,
            storedFutureEvidenceRebasedAtEpochMillis =
                whilePending.futureEvidenceRebasedAtEpochMillis,
            nowEpochMillis = afterOriginalFutureTimestamp,
        )
        assertEquals(firstObservedNow, afterPendingRemoval.effectiveApprovedAtEpochMillis)
        assertEquals(null, afterPendingRemoval.futureEvidenceEpochMillis)
        assertEquals(null, afterPendingRemoval.futureEvidenceRebasedAtEpochMillis)
    }
}
