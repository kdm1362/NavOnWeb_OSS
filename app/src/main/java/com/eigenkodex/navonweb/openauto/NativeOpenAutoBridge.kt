/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.openauto

import android.content.Context
import com.eigenkodex.navonweb.BuildConfig
import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredential
import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredentialCode
import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredentialProviderFactory
import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredentialResult
import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredentialSource
import com.eigenkodex.navonweb.openauto.credential.HeadUnitCredentialStatus
import com.eigenkodex.navonweb.openauto.protocol.AasdkTlsBuildBoundary
import com.eigenkodex.navonweb.openauto.protocol.AasdkTlsPeerTrustPolicyFactory
import com.eigenkodex.navonweb.openauto.protocol.AasdkTlsPeerTrustPolicy
import com.eigenkodex.navonweb.openauto.usb.AoaUsbHost

internal data class OpenAutoPreflight(
    val summary: String,
    val endpointMode: ProjectionEndpointMode,
    val endpointHost: String,
    val endpointPort: Int,
    val credential: HeadUnitCredential?,
    val credentialSource: HeadUnitCredentialSource,
    val buildBoundary: AasdkTlsBuildBoundary,
    val peerTrustPolicy: AasdkTlsPeerTrustPolicy,
) {
    fun runtimeConfig(
        benchSafetyConfirmed: Boolean,
        projectionConfig: OpenAutoConfig = ProjectionVideoProfile.FREE_800X480.toOpenAutoConfig(),
    ): AasdkProjectionRuntimeConfig? {
        val identity = credential ?: return null
        if (endpointHost.isBlank()) return null
        return AasdkProjectionRuntimeConfig(
            host = endpointHost,
            port = endpointPort,
            credential = identity,
            buildBoundary = buildBoundary,
            peerTrustPolicy = peerTrustPolicy,
            benchSafetyConfirmed = benchSafetyConfirmed,
            projectionConfig = projectionConfig,
        )
    }
}

object NativeOpenAutoBridge {
    private val libraryLoaded: Boolean by lazy {
        BuildConfig.NATIVE_OPENAUTO_ENABLED && runCatching {
            System.loadLibrary("openauto_android")
        }.isSuccess
    }

    /**
     * Performs local-only checks. It deliberately does not open TCP/TLS, so an
     * availability check cannot consume the phone's one-shot Head Unit Server
     * session and cannot run before the parked-bench safety decision.
     */
    internal fun preflight(
        context: Context,
    ): OpenAutoPreflight {
        val appContext = context.applicationContext
        val endpoint = ProjectionEndpointResolver.resolve()
        val credentialResult = runCatching {
            HeadUnitCredentialProviderFactory.create(appContext).load()
        }.getOrElse {
            HeadUnitCredentialResult.Unavailable(
                HeadUnitCredentialStatus(HeadUnitCredentialCode.KEYSTORE_ERROR),
            )
        }
        val credentialStatus = credentialResult.status
        val usbStatus = runCatching {
            AoaUsbHost(appContext).discover().status.safeSummary()
        }.getOrElse {
            "DISCOVERY_ERROR"
        }
        val buildBoundary = if (BuildConfig.DEBUG) {
            AasdkTlsBuildBoundary.DEBUG
        } else {
            AasdkTlsBuildBoundary.RELEASE
        }
        val peerTrustPolicy = AasdkTlsPeerTrustPolicyFactory.create()
        val peerTrustStatus = when (peerTrustPolicy) {
            AasdkTlsPeerTrustPolicy.PlatformTrust -> "PLATFORM"
            AasdkTlsPeerTrustPolicy.UnverifiedPeer -> "UNVERIFIED"
        }
        val endpointStatus = if (endpoint.isEnabled) {
            "${endpoint.mode.name}_CONFIGURED"
        } else {
            "${endpoint.mode.name}_DISABLED"
        }
        val runtimeStatus = when {
            !BuildConfig.NATIVE_OPENAUTO_ENABLED -> "NDK runtime disabled in the P0 build"
            !libraryLoaded -> "NDK runtime library unavailable"
            else -> nativePortStatus()
        }
        val summary = "AA TLS ${credentialStatus.safeSummary()} • peer trust $peerTrustStatus • " +
            "AOA USB $usbStatus • " +
            "AASDK endpoint $endpointStatus • $runtimeStatus"
        return OpenAutoPreflight(
            summary = summary,
            endpointMode = endpoint.mode,
            endpointHost = endpoint.host,
            endpointPort = endpoint.port,
            credential = (credentialResult as? HeadUnitCredentialResult.Ready)?.credential,
            credentialSource = credentialStatus.source,
            buildBoundary = buildBoundary,
            peerTrustPolicy = peerTrustPolicy,
        )
    }

    private external fun nativePortStatus(): String
}
