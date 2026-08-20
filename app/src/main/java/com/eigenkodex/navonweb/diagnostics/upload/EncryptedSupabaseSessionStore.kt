/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.diagnostics.upload

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SupabaseAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
) {
    fun isAccessTokenUsable(nowEpochSeconds: Long): Boolean =
        expiresAtEpochSeconds - ACCESS_TOKEN_SKEW_SECONDS > nowEpochSeconds

    private companion object {
        const val ACCESS_TOKEN_SKEW_SECONDS = 60L
    }
}

/** Stores anonymous Supabase tokens only as Android Keystore AES-GCM ciphertext. */
internal class EncryptedSupabaseSessionStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val authenticatedData =
        "${applicationContext.packageName}:supabase-anonymous-session:v1"
            .toByteArray(StandardCharsets.UTF_8)

    fun load(): SupabaseAuthSession? = synchronized(STORE_LOCK) {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return@synchronized null
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
            ?: return@synchronized null
        runCatching {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            require(iv.size in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES)
            require(ciphertext.size <= MAX_CIPHERTEXT_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(authenticatedData)
            decodeSession(cipher.doFinal(ciphertext))
        }.getOrElse {
            clearLocked()
            null
        }
    }

    fun save(session: SupabaseAuthSession): Boolean = synchronized(STORE_LOCK) {
        runCatching {
            validateSession(session)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(authenticatedData)
            val ciphertext = cipher.doFinal(encodeSession(session))
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
        }.getOrDefault(false)
    }

    fun clear() = synchronized(STORE_LOCK) { clearLocked() }

    private fun clearLocked() {
        preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encodeSession(session: SupabaseAuthSession): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeLong(session.expiresAtEpochSeconds)
                writeString(output, session.accessToken)
                writeString(output, session.refreshToken)
            }
            bytes.toByteArray()
        }

    private fun decodeSession(encoded: ByteArray): SupabaseAuthSession =
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            val expiresAtEpochSeconds = input.readLong()
            val session = SupabaseAuthSession(
                accessToken = readString(input),
                refreshToken = readString(input),
                expiresAtEpochSeconds = expiresAtEpochSeconds,
            )
            require(input.read() == -1)
            validateSession(session)
            session
        }

    private fun writeString(output: DataOutputStream, value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size in 1..MAX_TOKEN_BYTES)
        output.writeInt(encoded.size)
        output.write(encoded)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_TOKEN_BYTES)
        val encoded = ByteArray(length).also(input::readFully)
        return encoded.toString(StandardCharsets.UTF_8)
    }

    private fun validateSession(session: SupabaseAuthSession) {
        require(session.accessToken.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_TOKEN_BYTES)
        require(session.refreshToken.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_TOKEN_BYTES)
        require(session.expiresAtEpochSeconds > 0L)
    }

    private companion object {
        val STORE_LOCK = Any()
        const val PREFERENCES_NAME = "navonweb_supabase_session"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "navonweb_supabase_session_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val MIN_GCM_IV_BYTES = 12
        const val MAX_GCM_IV_BYTES = 16
        const val MAX_TOKEN_BYTES = 16 * 1_024
        const val MAX_CIPHERTEXT_BYTES = 2 * MAX_TOKEN_BYTES + 1_024
        const val FORMAT_VERSION = 1
    }
}
