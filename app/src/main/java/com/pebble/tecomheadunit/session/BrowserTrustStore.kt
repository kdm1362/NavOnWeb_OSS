/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.session

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

enum class BrowserDevicePermission {
    CONTROL,
    READ_ONLY,
}

/** Public, non-secret information that may be rendered in the phone settings UI. */
data class PairedBrowserDevice(
    val deviceId: String,
    val displayName: String,
    val permission: BrowserDevicePermission,
    val preferredMain: Boolean,
    /** Stable hint only. The live-session coordinator resolves collisions among active devices. */
    val colorSlot: Int,
    val createdAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
)

/** Authentication result for server-side authorization. ownerKey must never enter UI or logs. */
data class AuthenticatedBrowserDevice(
    val device: PairedBrowserDevice,
    val ownerKey: String,
)

/** The raw credential exists only in this one-time pairing result. */
data class IssuedBrowserCredential(
    val credential: String,
    val authenticatedDevice: AuthenticatedBrowserDevice,
)

sealed interface BrowserCredentialIssueResult {
    data class Issued(val grant: IssuedBrowserCredential) : BrowserCredentialIssueResult
    data object CapacityReached : BrowserCredentialIssueResult
    data object PersistenceFailed : BrowserCredentialIssueResult
}

sealed interface PairedBrowserMutationResult {
    data class Updated(
        val device: PairedBrowserDevice?,
        val preferredMainDeviceId: String?,
    ) : PairedBrowserMutationResult

    data object NotFound : PairedBrowserMutationResult
    data object NotAllowed : PairedBrowserMutationResult
    data object InvalidDisplayName : PairedBrowserMutationResult
    data object PersistenceFailed : PairedBrowserMutationResult
}

sealed interface PairedBrowserRemovalResult {
    data class Removed(
        val device: PairedBrowserDevice,
        /** Stable server-only identity used to close sessions belonging to the removed device. */
        val ownerKey: String,
    ) : PairedBrowserRemovalResult

    data object NotFound : PairedBrowserRemovalResult
    data object PersistenceFailed : PairedBrowserRemovalResult
}

internal data class StoredBrowserTrustState(
    val schemaVersion: Int,
    val encodedDevices: String?,
    val preferredMainDeviceId: String?,
    val legacyCredentialDigest: String?,
)

internal interface BrowserTrustPersistence {
    fun load(): StoredBrowserTrustState
    fun save(state: StoredBrowserTrustState): Boolean
}

/**
 * Persistent browser credential registry.
 *
 * Only SHA-256 credential digests are stored. The display model deliberately omits both those
 * digests and the server-only owner key. All instances in this process share one mutation lock so
 * the Activity and foreground service cannot overwrite each other's registry changes.
 */
class BrowserTrustStore internal constructor(
    private val persistence: BrowserTrustPersistence,
    private val credentialFactory: () -> String = BrowserCredential::create,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        persistence = SharedPreferencesBrowserTrustPersistence(context.applicationContext),
    )

    /** Compatibility path for callers that have not yet adopted device-aware pairing. */
    fun issue(): String = when (val result = issueDevice()) {
        is BrowserCredentialIssueResult.Issued -> result.grant.credential
        BrowserCredentialIssueResult.CapacityReached ->
            throw IllegalStateException("Paired browser capacity reached")
        BrowserCredentialIssueResult.PersistenceFailed ->
            throw IllegalStateException("Unable to persist paired browser")
    }

    /**
     * Adds one newly paired browser without replacing, revoking, or rewriting any prior device.
     *
     * Session lifetime is deliberately outside this store. Only [remove] and an explicit access
     * mutation may cause the server layer to close a live peer belonging to the affected device.
     */
    fun pairNewDevice(
        displayName: String? = null,
        permission: BrowserDevicePermission = BrowserDevicePermission.CONTROL,
    ): BrowserCredentialIssueResult = synchronized(STORAGE_LOCK) {
        appendDeviceLocked(displayName, permission)
    }

    /** Compatibility alias for callers and tests written before the additive API was named. */
    fun issueDevice(
        displayName: String? = null,
        permission: BrowserDevicePermission = BrowserDevicePermission.CONTROL,
    ): BrowserCredentialIssueResult = pairNewDevice(displayName, permission)

    private fun appendDeviceLocked(
        displayName: String?,
        permission: BrowserDevicePermission,
    ): BrowserCredentialIssueResult {
        val current = loadRegistry()
        if (current.devices.size >= MAX_PAIRED_BROWSER_DEVICES) {
            return BrowserCredentialIssueResult.CapacityReached
        }

        val issued = createUniqueCredential(current)
            ?: return BrowserCredentialIssueResult.PersistenceFailed
        val now = clock().coerceAtLeast(1L)
        val colorSlot = (0 until LIVE_COLOR_SLOT_COUNT)
            .minWithOrNull(
                compareBy<Int> { slot -> current.devices.count { it.colorSlot == slot } }
                    .thenBy { it },
            ) ?: 0
        val storedDevice = StoredPairedBrowserDevice(
            deviceId = BrowserCredential.deviceIdFromStoredDigest(issued.digest),
            credentialDigest = issued.digest,
            displayName = normalizeDisplayName(displayName, issued.digest),
            permission = permission,
            colorSlot = colorSlot,
            createdAtEpochMillis = now,
            lastSeenAtEpochMillis = now,
        )
        val preferredMainDeviceId = current.preferredMainDeviceId
            ?: storedDevice.deviceId.takeIf { permission == BrowserDevicePermission.CONTROL }
        val updated = current.copy(
            devices = current.devices + storedDevice,
            preferredMainDeviceId = preferredMainDeviceId,
        )
        if (!persistRegistry(updated)) {
            return BrowserCredentialIssueResult.PersistenceFailed
        }
        return BrowserCredentialIssueResult.Issued(
            IssuedBrowserCredential(
                credential = issued.credential,
                authenticatedDevice = authenticatedDevice(storedDevice, preferredMainDeviceId),
            ),
        )
    }

    fun authenticate(candidate: String?): AuthenticatedBrowserDevice? = synchronized(STORAGE_LOCK) {
        val registry = loadRegistry()
        val stored = registry.devices.firstOrNull { device ->
            BrowserCredential.isValid(candidate, device.credentialDigest)
        } ?: return@synchronized null
        authenticatedDevice(stored, registry.preferredMainDeviceId)
    }

    fun isValid(candidate: String?): Boolean = authenticate(candidate) != null

    /** True when at least one credential survived strict decoding or legacy migration. */
    fun hasStoredCredential(): Boolean = synchronized(STORAGE_LOCK) {
        loadRegistry().devices.isNotEmpty()
    }

    fun devices(): List<PairedBrowserDevice> = synchronized(STORAGE_LOCK) {
        val registry = loadRegistry()
        registry.devices
            .sortedWith(compareBy<StoredPairedBrowserDevice> { it.createdAtEpochMillis }.thenBy { it.deviceId })
            .map { it.toPublicDevice(registry.preferredMainDeviceId) }
    }

    fun preferredMainDeviceId(): String? = synchronized(STORAGE_LOCK) {
        loadRegistry().preferredMainDeviceId
    }

    /** Renames one device without changing credential, access, timestamps or session ownership. */
    fun renameDevice(
        deviceId: String,
        displayName: String,
    ): PairedBrowserMutationResult = synchronized(STORAGE_LOCK) {
        val current = loadRegistry()
        val index = current.devices.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) return@synchronized PairedBrowserMutationResult.NotFound
        val existing = current.devices[index]
        val normalized = normalizeRenamedDisplayName(displayName)
            ?: return@synchronized PairedBrowserMutationResult.InvalidDisplayName
        if (existing.displayName == normalized) {
            return@synchronized PairedBrowserMutationResult.Updated(
                existing.toPublicDevice(current.preferredMainDeviceId),
                current.preferredMainDeviceId,
            )
        }
        val replacement = existing.copy(displayName = normalized)
        val updatedDevices = current.devices.toMutableList().apply { set(index, replacement) }
        if (!persistRegistry(current.copy(devices = updatedDevices))) {
            return@synchronized PairedBrowserMutationResult.PersistenceFailed
        }
        PairedBrowserMutationResult.Updated(
            replacement.toPublicDevice(current.preferredMainDeviceId),
            current.preferredMainDeviceId,
        )
    }

    fun updateAccess(
        deviceId: String,
        permission: BrowserDevicePermission,
    ): PairedBrowserMutationResult = synchronized(STORAGE_LOCK) {
        val current = loadRegistry()
        val index = current.devices.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) return@synchronized PairedBrowserMutationResult.NotFound
        val existing = current.devices[index]
        val preferred = current.preferredMainDeviceId.takeUnless {
            it == deviceId && permission == BrowserDevicePermission.READ_ONLY
        }
        if (existing.permission == permission && preferred == current.preferredMainDeviceId) {
            return@synchronized PairedBrowserMutationResult.Updated(
                existing.toPublicDevice(preferred),
                preferred,
            )
        }
        val replacement = existing.copy(permission = permission)
        val updatedDevices = current.devices.toMutableList().apply { set(index, replacement) }
        if (!persistRegistry(current.copy(devices = updatedDevices, preferredMainDeviceId = preferred))) {
            return@synchronized PairedBrowserMutationResult.PersistenceFailed
        }
        PairedBrowserMutationResult.Updated(replacement.toPublicDevice(preferred), preferred)
    }

    /** A disconnected preferred main remains selected. Only explicit mutation changes it. */
    fun setPreferredMain(deviceId: String?): PairedBrowserMutationResult = synchronized(STORAGE_LOCK) {
        val current = loadRegistry()
        val selected = if (deviceId == null) {
            null
        } else {
            current.devices.firstOrNull { it.deviceId == deviceId }
                ?: return@synchronized PairedBrowserMutationResult.NotFound
        }
        if (selected?.permission == BrowserDevicePermission.READ_ONLY) {
            return@synchronized PairedBrowserMutationResult.NotAllowed
        }
        if (current.preferredMainDeviceId == deviceId) {
            return@synchronized PairedBrowserMutationResult.Updated(
                selected?.toPublicDevice(deviceId),
                deviceId,
            )
        }
        if (!persistRegistry(current.copy(preferredMainDeviceId = deviceId))) {
            return@synchronized PairedBrowserMutationResult.PersistenceFailed
        }
        PairedBrowserMutationResult.Updated(selected?.toPublicDevice(deviceId), deviceId)
    }

    /** Atomically claims an empty main slot without overwriting an explicit or earlier choice. */
    fun setPreferredMainIfUnset(deviceId: String): PairedBrowserMutationResult = synchronized(STORAGE_LOCK) {
        val current = loadRegistry()
        val candidate = current.devices.firstOrNull { it.deviceId == deviceId }
            ?: return@synchronized PairedBrowserMutationResult.NotFound
        if (candidate.permission == BrowserDevicePermission.READ_ONLY) {
            return@synchronized PairedBrowserMutationResult.NotAllowed
        }
        current.preferredMainDeviceId?.let { existingId ->
            val existing = current.devices.firstOrNull { it.deviceId == existingId }
            return@synchronized PairedBrowserMutationResult.Updated(
                existing?.toPublicDevice(existingId),
                existingId,
            )
        }
        if (!persistRegistry(current.copy(preferredMainDeviceId = deviceId))) {
            return@synchronized PairedBrowserMutationResult.PersistenceFailed
        }
        PairedBrowserMutationResult.Updated(candidate.toPublicDevice(deviceId), deviceId)
    }

    fun remove(deviceId: String): PairedBrowserRemovalResult = synchronized(STORAGE_LOCK) {
        val current = loadRegistry()
        val removed = current.devices.firstOrNull { it.deviceId == deviceId }
            ?: return@synchronized PairedBrowserRemovalResult.NotFound
        val preferred = current.preferredMainDeviceId.takeUnless { it == deviceId }
        val updated = current.copy(
            devices = current.devices.filterNot { it.deviceId == deviceId },
            preferredMainDeviceId = preferred,
        )
        if (!persistRegistry(updated)) {
            return@synchronized PairedBrowserRemovalResult.PersistenceFailed
        }
        PairedBrowserRemovalResult.Removed(
            device = removed.toPublicDevice(current.preferredMainDeviceId),
            ownerKey = BrowserCredential.ownerKeyFromStoredDigest(removed.credentialDigest),
        )
    }

    /** Called on session admission, not on every authenticated packet. */
    fun recordSeen(
        deviceId: String,
        epochMillis: Long = clock(),
    ): Boolean = synchronized(STORAGE_LOCK) {
        if (epochMillis <= 0L) return@synchronized false
        val current = loadRegistry()
        val index = current.devices.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) return@synchronized false
        val existing = current.devices[index]
        if (epochMillis <= existing.lastSeenAtEpochMillis) return@synchronized true
        val updatedDevices = current.devices.toMutableList().apply {
            set(index, existing.copy(lastSeenAtEpochMillis = epochMillis))
        }
        persistRegistry(current.copy(devices = updatedDevices))
    }

    private fun createUniqueCredential(current: StoredRegistry): PendingCredential? {
        repeat(MAX_CREDENTIAL_GENERATION_ATTEMPTS) {
            val credential = runCatching(credentialFactory).getOrNull() ?: return null
            val digest = runCatching { BrowserCredential.digest(credential) }.getOrNull() ?: return null
            if (
                BrowserCredential.isValid(credential, digest) &&
                current.devices.none { it.credentialDigest == digest }
            ) {
                return PendingCredential(credential, digest)
            }
        }
        return null
    }

    private fun loadRegistry(): StoredRegistry {
        val state = persistence.load()
        if (state.schemaVersion == REGISTRY_VERSION) {
            val decoded = BrowserTrustCodec.decode(state.encodedDevices)
            val preferred = state.preferredMainDeviceId?.takeIf { preferredId ->
                decoded.any {
                    it.deviceId == preferredId && it.permission == BrowserDevicePermission.CONTROL
                }
            }
            return StoredRegistry(decoded, preferred)
        }

        val legacyDigest = state.legacyCredentialDigest
            ?.takeIf(BrowserCredential::isStoredDigest)
            ?: return StoredRegistry.EMPTY
        val now = clock().coerceAtLeast(1L)
        val migrated = StoredPairedBrowserDevice(
            deviceId = BrowserCredential.deviceIdFromStoredDigest(legacyDigest),
            credentialDigest = legacyDigest,
            displayName = normalizeDisplayName(null, legacyDigest),
            permission = BrowserDevicePermission.CONTROL,
            colorSlot = 0,
            createdAtEpochMillis = now,
            lastSeenAtEpochMillis = now,
        )
        val registry = StoredRegistry(listOf(migrated), migrated.deviceId)
        // Migration is atomic. On failure the valid v1 digest remains authoritative and is never
        // revoked, so the previously paired browser can still authenticate on the next read.
        persistRegistry(registry)
        return registry
    }

    private fun persistRegistry(registry: StoredRegistry): Boolean = persistence.save(
        StoredBrowserTrustState(
            schemaVersion = REGISTRY_VERSION,
            encodedDevices = BrowserTrustCodec.encode(registry.devices),
            preferredMainDeviceId = registry.preferredMainDeviceId,
            legacyCredentialDigest = null,
        ),
    )

    private fun authenticatedDevice(
        stored: StoredPairedBrowserDevice,
        preferredMainDeviceId: String?,
    ): AuthenticatedBrowserDevice = AuthenticatedBrowserDevice(
        device = stored.toPublicDevice(preferredMainDeviceId),
        ownerKey = BrowserCredential.ownerKeyFromStoredDigest(stored.credentialDigest),
    )

    private fun normalizeDisplayName(candidate: String?, credentialDigest: String): String {
        val normalized = candidate
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.let { truncateCodePoints(it, MAX_DISPLAY_NAME_CODE_POINTS) }
            ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        if (normalized != null) return normalized
        val id = BrowserCredential.deviceIdFromStoredDigest(credentialDigest)
        return "Browser ${id.takeLast(4).uppercase(Locale.ROOT)}"
    }

    private fun normalizeRenamedDisplayName(candidate: String): String? {
        if (candidate.any(Char::isISOControl)) return null
        return candidate
            .trim()
            .replace(WHITESPACE, " ")
            .takeIf {
                it.isNotBlank() &&
                    it.codePointCount(0, it.length) <= MAX_DISPLAY_NAME_CODE_POINTS
            }
    }

    private companion object {
        val STORAGE_LOCK = Any()
        val WHITESPACE = Regex("\\s+")
        const val REGISTRY_VERSION = 2
        const val LIVE_COLOR_SLOT_COUNT = 3
        const val MAX_DISPLAY_NAME_CODE_POINTS = 64
        const val MAX_CREDENTIAL_GENERATION_ATTEMPTS = 8
    }
}

internal const val MAX_PAIRED_BROWSER_DEVICES = 32

private data class PendingCredential(
    val credential: String,
    val digest: String,
)

private data class StoredRegistry(
    val devices: List<StoredPairedBrowserDevice>,
    val preferredMainDeviceId: String?,
) {
    companion object {
        val EMPTY = StoredRegistry(emptyList(), null)
    }
}

private data class StoredPairedBrowserDevice(
    val deviceId: String,
    val credentialDigest: String,
    val displayName: String,
    val permission: BrowserDevicePermission,
    val colorSlot: Int,
    val createdAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
) {
    fun toPublicDevice(preferredMainDeviceId: String?): PairedBrowserDevice = PairedBrowserDevice(
        deviceId = deviceId,
        displayName = displayName,
        permission = permission,
        preferredMain = deviceId == preferredMainDeviceId,
        colorSlot = colorSlot,
        createdAtEpochMillis = createdAtEpochMillis,
        lastSeenAtEpochMillis = lastSeenAtEpochMillis,
    )
}

private object BrowserTrustCodec {
    private val fieldEncoder = Base64.getUrlEncoder().withoutPadding()
    private val fieldDecoder = Base64.getUrlDecoder()
    private val deviceIdPattern = Regex("browser_[A-Za-z0-9_-]{16}")

    fun encode(devices: List<StoredPairedBrowserDevice>): String = devices.joinToString("\n") { device ->
        listOf(
            device.deviceId,
            device.credentialDigest,
            encodeField(device.displayName),
            device.permission.name,
            device.colorSlot.toString(),
            device.createdAtEpochMillis.toString(),
            device.lastSeenAtEpochMillis.toString(),
        ).joinToString("\t")
    }

    fun decode(payload: String?): List<StoredPairedBrowserDevice> {
        if (payload.isNullOrEmpty()) return emptyList()
        val seenIds = mutableSetOf<String>()
        val seenDigests = mutableSetOf<String>()
        return payload.lineSequence()
            .take(MAX_PAIRED_BROWSER_DEVICES + 1)
            .mapNotNull(::decodeDevice)
            .filter { seenIds.add(it.deviceId) && seenDigests.add(it.credentialDigest) }
            .take(MAX_PAIRED_BROWSER_DEVICES)
            .toList()
    }

    private fun decodeDevice(line: String): StoredPairedBrowserDevice? {
        val fields = line.split('\t')
        if (fields.size != 7) return null
        val deviceId = fields[0].takeIf(deviceIdPattern::matches) ?: return null
        val credentialDigest = fields[1].takeIf(BrowserCredential::isStoredDigest) ?: return null
        if (BrowserCredential.deviceIdFromStoredDigest(credentialDigest) != deviceId) return null
        val displayName = decodeField(fields[2])
            ?.takeIf {
                it.isNotBlank() &&
                    it.codePointCount(0, it.length) <= 64 &&
                    it.none(Char::isISOControl)
            }
            ?: return null
        val permission = runCatching { BrowserDevicePermission.valueOf(fields[3]) }.getOrNull()
            ?: return null
        val colorSlot = fields[4].toIntOrNull()?.takeIf { it in 0..2 } ?: return null
        val createdAt = fields[5].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val lastSeenAt = fields[6].toLongOrNull()?.takeIf { it >= createdAt } ?: return null
        return StoredPairedBrowserDevice(
            deviceId = deviceId,
            credentialDigest = credentialDigest,
            displayName = displayName,
            permission = permission,
            colorSlot = colorSlot,
            createdAtEpochMillis = createdAt,
            lastSeenAtEpochMillis = lastSeenAt,
        )
    }

    private fun encodeField(value: String): String = fieldEncoder.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
    )

    private fun decodeField(value: String): String? = runCatching {
        fieldDecoder.decode(value).toString(StandardCharsets.UTF_8)
    }.getOrNull()
}

private fun truncateCodePoints(value: String, maximumCodePoints: Int): String {
    val count = value.codePointCount(0, value.length)
    if (count <= maximumCodePoints) return value
    return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints))
}

private class SharedPreferencesBrowserTrustPersistence(context: Context) : BrowserTrustPersistence {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredBrowserTrustState = StoredBrowserTrustState(
        schemaVersion = preferences.getInt(KEY_VERSION, 0),
        encodedDevices = preferences.getString(KEY_DEVICES, null),
        preferredMainDeviceId = preferences.getString(KEY_PREFERRED_MAIN_DEVICE_ID, null),
        legacyCredentialDigest = preferences.getString(KEY_LEGACY_TOKEN_DIGEST, null),
    )

    override fun save(state: StoredBrowserTrustState): Boolean {
        val editor = preferences.edit()
            .putInt(KEY_VERSION, state.schemaVersion)
            .putString(KEY_DEVICES, state.encodedDevices)
            .remove(KEY_LEGACY_TOKEN_DIGEST)
        if (state.preferredMainDeviceId == null) {
            editor.remove(KEY_PREFERRED_MAIN_DEVICE_ID)
        } else {
            editor.putString(KEY_PREFERRED_MAIN_DEVICE_ID, state.preferredMainDeviceId)
        }
        return editor.commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "trusted_browser"
        const val KEY_VERSION = "credential_version"
        const val KEY_LEGACY_TOKEN_DIGEST = "token_sha256"
        const val KEY_DEVICES = "paired_devices_v2"
        const val KEY_PREFERRED_MAIN_DEVICE_ID = "preferred_main_device_id"
    }
}
