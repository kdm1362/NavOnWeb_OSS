/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.diagnostics.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploadConsentGateTest {
    @Test
    fun requestingConfirmationDoesNotSubmitAndConfirmSubmitsExactlyOnce() {
        val submitted = mutableListOf<String>()
        val gate = DiagnosticUploadConsentGate(submitted::add)

        gate.requestConfirmation("음성비서 호출 뒤 연결 종료")

        assertTrue(gate.hasPendingConfirmation)
        assertTrue(submitted.isEmpty())

        gate.confirm()
        gate.confirm()

        assertFalse(gate.hasPendingConfirmation)
        assertEquals(listOf("음성비서 호출 뒤 연결 종료"), submitted)
    }

    @Test
    fun cancellationHasNoSubmissionSideEffect() {
        val submitted = mutableListOf<String>()
        val gate = DiagnosticUploadConsentGate(submitted::add)

        gate.requestConfirmation("취소할 설명")
        gate.cancel()
        gate.confirm()

        assertFalse(gate.hasPendingConfirmation)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun consentCopyIsPinnedExactly() {
        assertEquals(
            "개발자에게 진단데이터를 전송합니다. 진단 데이터 확인 및 분석에 동의하신다면 계속해 주세요",
            DIAGNOSTIC_UPLOAD_CONSENT_MESSAGE,
        )
    }
}
