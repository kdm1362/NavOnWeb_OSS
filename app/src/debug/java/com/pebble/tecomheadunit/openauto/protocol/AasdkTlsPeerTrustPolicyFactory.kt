/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

/** The upstream AASDK bench does not expose an approved phone-side trust chain. */
internal object AasdkTlsPeerTrustPolicyFactory {
    fun create(): AasdkTlsPeerTrustPolicy =
        AasdkTlsPeerTrustPolicy.DebugOnlyUnverifiedPeer
}
