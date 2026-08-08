/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

/**
 * Public corresponding-source placeholder for the deployment-supplied release identity.
 *
 * Production certificates and private keys are external deployment inputs and are deliberately
 * absent from this repository. A build made directly from this tree therefore fails closed until
 * an authorized deployment process supplies an equivalent private implementation.
 */
internal class BundledAasdkCredentialProvider : HeadUnitCredentialProvider {
    override fun load(): HeadUnitCredentialResult = unavailable(
        HeadUnitCredentialCode.CERT_NOT_PROVISIONED,
        HeadUnitCredentialSource.BUNDLED_RELEASE,
    )
}
