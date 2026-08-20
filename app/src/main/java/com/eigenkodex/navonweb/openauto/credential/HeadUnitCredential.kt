/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.credential

import java.security.PrivateKey
import java.security.cert.X509Certificate

enum class HeadUnitCredentialSource {
    NONE,
    DEVELOPMENT_PEM,
    BUNDLED_RELEASE,
    ANDROID_KEYSTORE,
}

enum class HeadUnitCredentialCode {
    LOCAL_CREDENTIAL_VALID,
    CERT_NOT_PROVISIONED,
    INCOMPLETE_CONFIGURATION,
    FILE_POLICY_VIOLATION,
    MATERIAL_TOO_LARGE,
    INVALID_CERTIFICATE,
    INVALID_PRIVATE_KEY,
    CERTIFICATE_EXPIRED,
    CERTIFICATE_NOT_YET_VALID,
    INCOMPATIBLE_KEY_USAGE,
    INVALID_CERTIFICATE_CHAIN,
    UNSUPPORTED_KEY_ALGORITHM,
    KEY_MISMATCH,
    KEY_ACCESS_ERROR,
    KEYSTORE_ERROR,
    HARDWARE_SECURITY_REQUIRED,
    TRUST_POLICY_NOT_CONFIGURED,
    UNTRUSTED_CERTIFICATE,
    DEVELOPMENT_CREDENTIALS_FORBIDDEN,
}

data class HeadUnitCredentialStatus(
    val code: HeadUnitCredentialCode,
    val source: HeadUnitCredentialSource = HeadUnitCredentialSource.NONE,
    val leafFingerprintSha256: String? = null,
) {
    fun safeSummary(): String {
        val message = when (code) {
            HeadUnitCredentialCode.LOCAL_CREDENTIAL_VALID -> "로컬 자격증명 검사 통과"
            HeadUnitCredentialCode.CERT_NOT_PROVISIONED -> "자격증명 미설정"
            HeadUnitCredentialCode.INCOMPLETE_CONFIGURATION -> "인증서와 개인키 구성이 불완전함"
            HeadUnitCredentialCode.FILE_POLICY_VIOLATION -> "개발 인증서 파일 정책 위반"
            HeadUnitCredentialCode.MATERIAL_TOO_LARGE -> "인증서 파일 크기 제한 초과"
            HeadUnitCredentialCode.INVALID_CERTIFICATE -> "인증서를 해석할 수 없음"
            HeadUnitCredentialCode.INVALID_PRIVATE_KEY -> "개인키를 해석할 수 없음"
            HeadUnitCredentialCode.CERTIFICATE_EXPIRED -> "인증서 만료"
            HeadUnitCredentialCode.CERTIFICATE_NOT_YET_VALID -> "인증서 유효기간 시작 전"
            HeadUnitCredentialCode.INCOMPATIBLE_KEY_USAGE -> "Android Auto 클라이언트 용도와 맞지 않는 인증서"
            HeadUnitCredentialCode.INVALID_CERTIFICATE_CHAIN -> "인증서 체인 순서 또는 서명 오류"
            HeadUnitCredentialCode.UNSUPPORTED_KEY_ALGORITHM -> "지원하지 않는 키 알고리즘"
            HeadUnitCredentialCode.KEY_MISMATCH -> "인증서와 개인키 불일치"
            HeadUnitCredentialCode.KEY_ACCESS_ERROR -> "개인키 접근 실패"
            HeadUnitCredentialCode.KEYSTORE_ERROR -> "Android Keystore 접근 실패"
            HeadUnitCredentialCode.HARDWARE_SECURITY_REQUIRED -> "하드웨어 보호 Keystore 키가 필요함"
            HeadUnitCredentialCode.TRUST_POLICY_NOT_CONFIGURED -> "승인된 프로젝션 인증서 신뢰 정책 미설정"
            HeadUnitCredentialCode.UNTRUSTED_CERTIFICATE -> "승인되지 않은 프로젝션 인증서"
            HeadUnitCredentialCode.DEVELOPMENT_CREDENTIALS_FORBIDDEN -> "개발용 파일 자격증명이 금지된 빌드"
        }
        val sourceLabel = when (source) {
            HeadUnitCredentialSource.NONE -> null
            HeadUnitCredentialSource.DEVELOPMENT_PEM -> "개발 PEM"
            HeadUnitCredentialSource.BUNDLED_RELEASE -> "bundled release"
            HeadUnitCredentialSource.ANDROID_KEYSTORE -> "Android Keystore"
        }
        val fingerprint = leafFingerprintSha256?.take(12)?.let { " · SHA-256 $it…" }.orEmpty()
        return buildString {
            append(message)
            append(" [")
            append(code.name)
            append(']')
            if (sourceLabel != null) append(" · $sourceLabel")
            append(fingerprint)
        }
    }
}

class HeadUnitCredential internal constructor(
    certificateChain: List<X509Certificate>,
    val privateKey: PrivateKey,
    val source: HeadUnitCredentialSource,
    val leafFingerprintSha256: String,
) {
    val certificateChain: List<X509Certificate> = certificateChain.toList()

    override fun toString(): String =
        "HeadUnitCredential(source=$source, certificateCount=${certificateChain.size}, privateKey=<redacted>)"
}

sealed interface HeadUnitCredentialResult {
    val status: HeadUnitCredentialStatus

    data class Ready(val credential: HeadUnitCredential) : HeadUnitCredentialResult {
        override val status = HeadUnitCredentialStatus(
            code = HeadUnitCredentialCode.LOCAL_CREDENTIAL_VALID,
            source = credential.source,
            leafFingerprintSha256 = credential.leafFingerprintSha256,
        )
    }

    data class Unavailable(override val status: HeadUnitCredentialStatus) : HeadUnitCredentialResult
}

fun interface HeadUnitCredentialProvider {
    fun load(): HeadUnitCredentialResult
}

internal fun unavailable(
    code: HeadUnitCredentialCode,
    source: HeadUnitCredentialSource = HeadUnitCredentialSource.NONE,
): HeadUnitCredentialResult.Unavailable =
    HeadUnitCredentialResult.Unavailable(HeadUnitCredentialStatus(code = code, source = source))
