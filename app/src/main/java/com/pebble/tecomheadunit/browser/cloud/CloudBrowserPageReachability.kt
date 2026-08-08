/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.cloud

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
 * Keeps a short network interruption from replacing the public browser address with a LAN address.
 * A successful probe restores the public address immediately.
 */
internal class CloudBrowserPageAvailabilityTracker(
    private val failuresBeforeFallback: Int = DEFAULT_FAILURES_BEFORE_FALLBACK,
) {
    init {
        require(failuresBeforeFallback > 0)
    }

    var availability: CloudBrowserPageAvailability = CloudBrowserPageAvailability.UNKNOWN
        private set

    private var consecutiveFailures = 0

    fun record(reachable: Boolean): CloudBrowserPageAvailability {
        if (reachable) {
            consecutiveFailures = 0
            availability = CloudBrowserPageAvailability.REACHABLE
        } else {
            consecutiveFailures += 1
            if (consecutiveFailures >= failuresBeforeFallback) {
                availability = CloudBrowserPageAvailability.UNREACHABLE
            }
        }
        return availability
    }

    private companion object {
        const val DEFAULT_FAILURES_BEFORE_FALLBACK = 2
    }
}

internal object BrowserAddressPolicy {
    fun select(
        cloudUrl: String?,
        localUrl: String,
        cloudAvailability: CloudBrowserPageAvailability,
    ): String = if (
        cloudUrl != null && cloudAvailability != CloudBrowserPageAvailability.UNREACHABLE
    ) {
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
                response.code in 200..399
            }
        }.getOrDefault(false)
    }

    override fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 5L
    }
}
