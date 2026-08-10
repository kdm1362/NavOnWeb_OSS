/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import com.pebble.tecomheadunit.BuildConfig

/**
 * Loads a bundled AASDK identity supplied as an external build input.
 *
 * Credential values are generated into BuildConfig by the release build. They are not stored in
 * this repository and must be supplied as external build inputs. Without those inputs, the
 * provider remains unavailable at runtime.
 */
internal class BundledAasdkCredentialProvider(
    private val validator: HeadUnitCredentialValidator = HeadUnitCredentialValidator(),
) : HeadUnitCredentialProvider {
    override fun load(): HeadUnitCredentialResult {
        val certificatePem =
            BuildConfig.BUNDLED_AASDK_CERTIFICATE_PEM.toByteArray(Charsets.US_ASCII)
        val privateKeyPem =
            BuildConfig.BUNDLED_AASDK_PRIVATE_KEY_PEM.toByteArray(Charsets.US_ASCII)
        if (certificatePem.isEmpty() || privateKeyPem.isEmpty()) {
            certificatePem.fill(0)
            privateKeyPem.fill(0)
            return unavailable(
                HeadUnitCredentialCode.CERT_NOT_PROVISIONED,
                HeadUnitCredentialSource.BUNDLED_RELEASE,
            )
        }
        return try {
            val certificates = PemCredentialParser.parseCertificates(certificatePem)
            val privateKey = PemCredentialParser.parsePrivateKey(privateKeyPem)
            validator.validate(
                certificateChain = certificates,
                privateKey = privateKey,
                source = HeadUnitCredentialSource.BUNDLED_RELEASE,
            )
        } catch (error: PemCredentialException) {
            unavailable(error.code, HeadUnitCredentialSource.BUNDLED_RELEASE)
        } catch (_: Exception) {
            unavailable(
                HeadUnitCredentialCode.INVALID_CERTIFICATE,
                HeadUnitCredentialSource.BUNDLED_RELEASE,
            )
        } finally {
            certificatePem.fill(0)
            privateKeyPem.fill(0)
        }
    }
}
