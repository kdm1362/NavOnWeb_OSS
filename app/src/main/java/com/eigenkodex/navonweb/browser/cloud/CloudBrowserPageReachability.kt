/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import android.util.Log
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class CloudBrowserPageAvailability {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE,
}

/**
 * Turns completed page probes into the displayed-address authority. A single failed probe does
 * not move the address: an origin reached over a relay competes with the projection stream for
 * the same radio, so isolated failures are expected and switching on one of them would churn the
 * address the user is reading. Recovery is immediate, so the public address returns as soon as
 * one probe succeeds.
 */
internal class CloudBrowserPageAvailabilityTracker(
    private val failuresToDeclareUnreachable: Int = DEFAULT_FAILURES_TO_DECLARE_UNREACHABLE,
) {
    var availability: CloudBrowserPageAvailability = CloudBrowserPageAvailability.UNKNOWN
        private set
    private var failureStreak = 0

    fun record(reachable: Boolean): CloudBrowserPageAvailability {
        if (reachable) {
            failureStreak = 0
            availability = CloudBrowserPageAvailability.REACHABLE
            return availability
        }
        failureStreak += 1
        if (failureStreak >= failuresToDeclareUnreachable) {
            availability = CloudBrowserPageAvailability.UNREACHABLE
        }
        return availability
    }

    private companion object {
        const val DEFAULT_FAILURES_TO_DECLARE_UNREACHABLE = 2
    }
}

internal object BrowserAddressPolicy {
    fun select(
        cloudUrl: String?,
        localUrl: String,
        cloudAvailability: CloudBrowserPageAvailability,
    ): String = if (cloudUrl != null && cloudAvailability == CloudBrowserPageAvailability.REACHABLE) {
        cloudUrl
    } else {
        localUrl
    }
}

/** Performs a real HTTPS request to the same public entry point shown to the user. */
internal class CloudBrowserPageProbe(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build(),
) : Closeable {
    suspend fun isReachable(browserUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(browserUrl)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..399) {
                    Log.i(PROBE_LOG_TAG, "CLOUD_BROWSER_PROBE_STATUS code=${response.code}")
                }
                response.code in 200..399
            }
        }.getOrElse { error ->
            // The reason matters: a refused connection, a TLS failure, and a timeout call for
            // different responses, and reporting only "unreachable" hides all three.
            Log.i(PROBE_LOG_TAG, "CLOUD_BROWSER_PROBE_FAILED ${error.javaClass.simpleName}")
            false
        }
    }

    override fun close() {
        httpClient.releaseSocketsOffCallerThread(cancelInFlightCalls = true)
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 5L
        const val PROBE_LOG_TAG = "NavOnWebCloudState"
    }
}
