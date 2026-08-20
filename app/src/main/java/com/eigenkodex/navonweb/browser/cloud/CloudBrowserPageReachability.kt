/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

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

/** Uses the latest completed public-page probe as the displayed-address authority. */
internal class CloudBrowserPageAvailabilityTracker {
    var availability: CloudBrowserPageAvailability = CloudBrowserPageAvailability.UNKNOWN
        private set

    fun record(reachable: Boolean): CloudBrowserPageAvailability {
        availability = if (reachable) {
            CloudBrowserPageAvailability.REACHABLE
        } else {
            CloudBrowserPageAvailability.UNREACHABLE
        }
        return availability
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
