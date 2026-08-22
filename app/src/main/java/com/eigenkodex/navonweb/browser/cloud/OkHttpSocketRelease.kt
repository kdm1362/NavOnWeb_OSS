/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser.cloud

import okhttp3.OkHttpClient

/**
 * Releases an OkHttp client's sockets without doing network work on the calling thread.
 *
 * Cancelling in-flight calls and evicting the connection pool both close live sockets, and
 * closing a TLS socket writes a close_notify record. These clients are closed from the
 * projection service scope, which runs on `Dispatchers.Main.immediate`, so that write raises
 * `NetworkOnMainThreadException` and takes the process down. The work is best-effort cleanup
 * with nothing to report back, so a short-lived daemon thread is enough; the caller returns
 * immediately and the client is already marked closed, so no new request can start.
 */
internal fun OkHttpClient.releaseSocketsOffCallerThread(cancelInFlightCalls: Boolean) {
    val dispatcher = dispatcher
    val pool = connectionPool
    Thread({
        if (cancelInFlightCalls) {
            runCatching { dispatcher.cancelAll() }
        }
        runCatching { dispatcher.executorService.shutdown() }
        runCatching { pool.evictAll() }
    }, RELEASE_THREAD_NAME).apply { isDaemon = true }.start()
}

private const val RELEASE_THREAD_NAME = "navonweb-okhttp-release"
