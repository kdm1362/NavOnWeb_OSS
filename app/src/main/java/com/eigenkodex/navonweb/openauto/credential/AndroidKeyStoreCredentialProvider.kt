/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto.credential

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.X509Certificate

internal enum class HeadUnitCredentialTrustDecision {
    TRUSTED,
    UNTRUSTED,
    NOT_CONFIGURED,
}

internal fun interface HeadUnitCredentialTrustPolicy {
    fun evaluate(certificateChain: List<X509Certificate>): HeadUnitCredentialTrustDecision
}

internal object UnconfiguredHeadUnitCredentialTrustPolicy : HeadUnitCredentialTrustPolicy {
    override fun evaluate(certificateChain: List<X509Certificate>) =
        HeadUnitCredentialTrustDecision.NOT_CONFIGURED
}

internal fun acceptsHardwareSecurity(
    sdkInt: Int,
    securityLevel: Int?,
    legacyInsideSecureHardware: Boolean,
): Boolean = if (sdkInt >= Build.VERSION_CODES.S) {
    securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
        securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
} else {
    legacyInsideSecureHardware
}

internal class AndroidKeyStoreCredentialProvider(
    private val validator: HeadUnitCredentialValidator = HeadUnitCredentialValidator(),
    private val requireHardwareBacked: Boolean = true,
    private val trustPolicy: HeadUnitCredentialTrustPolicy =
        UnconfiguredHeadUnitCredentialTrustPolicy,
    private val keyAlias: String = KEY_ALIAS,
) : HeadUnitCredentialProvider {
    override fun load(): HeadUnitCredentialResult = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            return unavailable(
                HeadUnitCredentialCode.CERT_NOT_PROVISIONED,
                HeadUnitCredentialSource.ANDROID_KEYSTORE,
            )
        }

        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
            ?: return unavailable(
                HeadUnitCredentialCode.KEY_ACCESS_ERROR,
                HeadUnitCredentialSource.ANDROID_KEYSTORE,
            )
        val privateKey = entry.privateKey
        val unexpectedlyExportedKey = privateKey.encoded
        if (unexpectedlyExportedKey != null) {
            unexpectedlyExportedKey.fill(0)
            return unavailable(
                HeadUnitCredentialCode.KEY_ACCESS_ERROR,
                HeadUnitCredentialSource.ANDROID_KEYSTORE,
            )
        }
        if (requireHardwareBacked && !isHardwareBacked(privateKey)) {
            return unavailable(
                HeadUnitCredentialCode.HARDWARE_SECURITY_REQUIRED,
                HeadUnitCredentialSource.ANDROID_KEYSTORE,
            )
        }
        val rawChain = entry.certificateChain?.toList().orEmpty()
        val certificateChain = rawChain.filterIsInstance<X509Certificate>()
        if (certificateChain.size != rawChain.size || certificateChain.isEmpty()) {
            return unavailable(
                HeadUnitCredentialCode.INVALID_CERTIFICATE_CHAIN,
                HeadUnitCredentialSource.ANDROID_KEYSTORE,
            )
        }
        val validation = validator.validate(
            certificateChain = certificateChain,
            privateKey = privateKey,
            source = HeadUnitCredentialSource.ANDROID_KEYSTORE,
        )
        if (validation !is HeadUnitCredentialResult.Ready) return validation
        when (trustPolicy.evaluate(certificateChain)) {
            HeadUnitCredentialTrustDecision.TRUSTED -> validation
            HeadUnitCredentialTrustDecision.UNTRUSTED -> unavailable(
                HeadUnitCredentialCode.UNTRUSTED_CERTIFICATE,
                HeadUnitCredentialSource.ANDROID_KEYSTORE,
            )
            HeadUnitCredentialTrustDecision.NOT_CONFIGURED -> unavailable(
                HeadUnitCredentialCode.TRUST_POLICY_NOT_CONFIGURED,
                HeadUnitCredentialSource.ANDROID_KEYSTORE,
            )
        }
    } catch (_: Exception) {
        unavailable(
            HeadUnitCredentialCode.KEYSTORE_ERROR,
            HeadUnitCredentialSource.ANDROID_KEYSTORE,
        )
    }

    private fun isHardwareBacked(privateKey: java.security.PrivateKey): Boolean = try {
        val keyInfo = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(privateKey, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            acceptsHardwareSecurity(
                sdkInt = Build.VERSION.SDK_INT,
                securityLevel = keyInfo.securityLevel,
                legacyInsideSecureHardware = false,
            )
        } else {
            @Suppress("DEPRECATION")
            val legacyInsideSecureHardware = keyInfo.isInsideSecureHardware
            acceptsHardwareSecurity(
                sdkInt = Build.VERSION.SDK_INT,
                securityLevel = null,
                legacyInsideSecureHardware = legacyInsideSecureHardware,
            )
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val KEY_ALIAS = "aa_head_unit_identity_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
