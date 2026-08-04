/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.protocol

import com.pebble.tecomheadunit.BuildConfig

/** Release accepts only explicitly approved phone TLS leaf fingerprints. */
internal object AasdkTlsPeerTrustPolicyFactory {
    fun create(): AasdkTlsPeerTrustPolicy {
        val pins = BuildConfig.AASDK_PHONE_PEER_LEAF_SHA256
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        if (pins.isEmpty()) return AasdkTlsPeerTrustPolicy.NotConfigured
        return runCatching<AasdkTlsPeerTrustPolicy> {
            AasdkTlsPeerTrustPolicy.PinnedLeafSha256(pins)
        }.getOrElse { AasdkTlsPeerTrustPolicy.NotConfigured }
    }
}
