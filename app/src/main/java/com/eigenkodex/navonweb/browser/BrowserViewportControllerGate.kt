/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.browser

/**
 * Keeps multiple trusted tabs from repeatedly replacing the global Android Auto viewport.
 * A foreground page refreshes its lease through the normal status poll. Another page can take
 * control only after the active page has stopped polling long enough for the lease to expire.
 */
internal class BrowserViewportControllerGate(
    private val nowMillis: () -> Long,
    private val leaseMillis: Long = DEFAULT_LEASE_MILLIS,
) {
    private var lease: Lease? = null

    init {
        require(leaseMillis > 0L) { "viewport controller lease must be positive" }
    }

    @Synchronized
    fun tryAcquire(browserCredential: String, clientId: String): Boolean {
        require(browserCredential.isNotBlank()) { "browser credential is required" }
        require(clientId.isNotBlank()) { "viewport client id is required" }
        val now = nowMillis()
        val current = lease
        val sameOwner = current?.browserCredential == browserCredential && current.clientId == clientId
        val expired = current == null || elapsed(now, current.lastSeenMillis) >= leaseMillis
        if (!sameOwner && !expired) return false
        lease = Lease(browserCredential, clientId, now)
        return true
    }

    @Synchronized
    fun refresh(browserCredential: String, clientId: String): Boolean {
        val current = lease ?: return false
        if (current.browserCredential != browserCredential || current.clientId != clientId) return false
        lease = current.copy(lastSeenMillis = nowMillis())
        return true
    }

    private fun elapsed(now: Long, then: Long): Long = (now - then).coerceAtLeast(0L)

    private data class Lease(
        val browserCredential: String,
        val clientId: String,
        val lastSeenMillis: Long,
    )

    companion object {
        const val DEFAULT_LEASE_MILLIS = 15_000L
    }
}
