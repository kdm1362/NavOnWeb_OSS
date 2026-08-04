/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.browser.cloud

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal const val MAX_SAFE_PAIRING_PUBLICATION_EPOCH = 9_007_199_254_740_991L

internal object PairingPublicationEpochCounter {
    fun next(current: Long): Long {
        check(current in 0 until MAX_SAFE_PAIRING_PUBLICATION_EPOCH) {
            "Invalid or exhausted cloud pairing publication epoch"
        }
        return current + 1L
    }
}

internal class PairingPublicationEpochAllocator(
    private val readCurrent: () -> Long,
    private val commitNext: (Long) -> Boolean,
    private val monitor: Any = Any(),
) {
    fun next(): Long = synchronized(monitor) {
        val current = runCatching(readCurrent).getOrElse { error ->
            throw IllegalStateException("Invalid cloud pairing publication epoch", error)
        }
        val next = PairingPublicationEpochCounter.next(current)
        check(commitNext(next)) { "Unable to persist cloud pairing publication epoch" }
        next
    }
}

data class CloudRelayIdentity(
    val deviceSecret: String,
    val roomId: String,
) {
    init {
        require(DEVICE_SECRET_PATTERN.matches(deviceSecret)) { "invalid relay device secret" }
        require(ROOM_ID_PATTERN.matches(roomId)) { "invalid relay room id" }
        require(roomId == roomIdForSecret(deviceSecret)) { "relay room does not match its secret" }
    }

    companion object {
        private const val ROOM_ID_CHARS = 22
        private val DEVICE_SECRET_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        private val ROOM_ID_PATTERN = Regex("^[A-Za-z0-9_-]{$ROOM_ID_CHARS}$")

        fun fromSecret(secret: String): CloudRelayIdentity =
            CloudRelayIdentity(secret, roomIdForSecret(secret))

        fun generate(random: SecureRandom = SecureRandom()): CloudRelayIdentity {
            val bytes = ByteArray(32).also(random::nextBytes)
            val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return fromSecret(secret)
        }

        fun roomIdForSecret(secret: String): String {
            require(DEVICE_SECRET_PATTERN.matches(secret)) { "invalid relay device secret" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(secret.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(ROOM_ID_CHARS)
        }
    }
}

/**
 * Persists a random routing secret in the app sandbox. Android backup is disabled for NavOnWeb,
 * so reinstalling intentionally produces a new room and invalidates old cloud URLs.
 */
class CloudRelayIdentityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val epochAllocator = PairingPublicationEpochAllocator(
        readCurrent = { preferences.getLong(KEY_PAIRING_PUBLICATION_EPOCH, 0L) },
        commitNext = { next ->
            preferences.edit()
                .putLong(KEY_PAIRING_PUBLICATION_EPOCH, next)
                .commit()
        },
        monitor = PROCESS_IDENTITY_LOCK,
    )

    fun getOrCreate(): CloudRelayIdentity = synchronized(PROCESS_IDENTITY_LOCK) {
        getOrCreateLocked()
    }

    private fun getOrCreateLocked(): CloudRelayIdentity {
        if (preferences.getInt(KEY_VERSION, 0) == FORMAT_VERSION) {
            preferences.getString(KEY_DEVICE_SECRET, null)?.let { stored ->
                runCatching { CloudRelayIdentity.fromSecret(stored) }.getOrNull()?.let { return it }
            }
        }
        val created = CloudRelayIdentity.generate()
        check(
            preferences.edit()
                .clear()
                .putInt(KEY_VERSION, FORMAT_VERSION)
                .putString(KEY_DEVICE_SECRET, created.deviceSecret)
                .putLong(KEY_PAIRING_PUBLICATION_EPOCH, 0L)
                .commit(),
        ) { "Unable to persist cloud relay identity" }
        return created
    }

    /**
     * Commits a strictly increasing publication epoch before the corresponding pairing code can
     * be sent. A corrupt or exhausted counter fails closed; it is never wrapped or silently reset.
     */
    fun nextPairingPublicationEpoch(): Long = synchronized(PROCESS_IDENTITY_LOCK) {
        getOrCreateLocked()
        epochAllocator.next()
    }

    private companion object {
        const val PREFERENCES_NAME = "navonweb_cloud_relay_identity"
        const val KEY_VERSION = "version"
        const val KEY_DEVICE_SECRET = "device_secret"
        const val KEY_PAIRING_PUBLICATION_EPOCH = "pairing_publication_epoch"
        const val FORMAT_VERSION = 1
        val PROCESS_IDENTITY_LOCK = Any()
    }
}
