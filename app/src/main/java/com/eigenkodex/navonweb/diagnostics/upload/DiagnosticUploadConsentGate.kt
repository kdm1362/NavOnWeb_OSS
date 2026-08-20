/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

internal const val DIAGNOSTIC_UPLOAD_CONSENT_MESSAGE =
    "개발자에게 진단데이터를 전송합니다. 진단 데이터 확인 및 분석에 동의하신다면 계속해 주세요"
internal const val DIAGNOSTIC_UPLOAD_CONSENT_VERSION = 1

/**
 * Holds a description only while the explicit consent dialog is visible.
 * Requesting or cancelling consent cannot invoke the approved submission callback.
 */
internal class DiagnosticUploadConsentGate(
    private val onApproved: (String) -> Unit,
) {
    private var pendingDescription: String? = null

    val hasPendingConfirmation: Boolean
        get() = pendingDescription != null

    fun requestConfirmation(userDescription: String) {
        pendingDescription = userDescription
    }

    fun confirm() {
        val approvedDescription = pendingDescription ?: return
        pendingDescription = null
        onApproved(approvedDescription)
    }

    fun cancel() {
        pendingDescription = null
    }
}
