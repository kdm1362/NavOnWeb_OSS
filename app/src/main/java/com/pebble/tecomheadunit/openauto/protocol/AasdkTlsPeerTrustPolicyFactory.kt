/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

/** Android Auto Head Unit Server peer certificates are not statically pinned. */
internal object AasdkTlsPeerTrustPolicyFactory {
    fun create(): AasdkTlsPeerTrustPolicy = AasdkTlsPeerTrustPolicy.UnverifiedPeer
}
