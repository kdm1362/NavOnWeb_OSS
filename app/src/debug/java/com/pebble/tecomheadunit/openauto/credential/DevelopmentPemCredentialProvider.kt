/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.openauto.credential

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.pebble.tecomheadunit.BuildConfig
import java.io.File

internal class DevelopmentPemCredentialProvider(
    context: Context,
    private val validator: HeadUnitCredentialValidator = HeadUnitCredentialValidator(),
) : HeadUnitCredentialProvider {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)

    override fun load(): HeadUnitCredentialResult {
        if (!BuildConfig.ALLOW_DEV_HEAD_UNIT_CREDENTIALS) {
            return unavailable(
                HeadUnitCredentialCode.DEVELOPMENT_CREDENTIALS_FORBIDDEN,
                HeadUnitCredentialSource.DEVELOPMENT_PEM,
            )
        }

        val directoryExists = existsWithoutFollowing(directory)
            ?: return unavailable(
                HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                HeadUnitCredentialSource.DEVELOPMENT_PEM,
            )
        if (!directoryExists) {
            return unavailable(HeadUnitCredentialCode.CERT_NOT_PROVISIONED)
        }
        if (!hasSecureDirectoryPolicy(directory)) {
            Log.w(LOG_TAG, "Debug credential file rejected: directory-policy")
            return unavailable(
                HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                HeadUnitCredentialSource.DEVELOPMENT_PEM,
            )
        }

        val certificateFile = File(directory, CERTIFICATE_FILE_NAME)
        val privateKeyFile = File(directory, PRIVATE_KEY_FILE_NAME)
        val certificateExists = existsWithoutFollowing(certificateFile)
        val privateKeyExists = existsWithoutFollowing(privateKeyFile)
        if (certificateExists == null || privateKeyExists == null) {
            Log.w(LOG_TAG, "Debug credential file rejected: presence-check")
            return unavailable(
                HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                HeadUnitCredentialSource.DEVELOPMENT_PEM,
            )
        }
        if (!certificateExists && !privateKeyExists) {
            return unavailable(HeadUnitCredentialCode.CERT_NOT_PROVISIONED)
        }
        if (!certificateExists || !privateKeyExists) {
            return unavailable(
                HeadUnitCredentialCode.INCOMPLETE_CONFIGURATION,
                HeadUnitCredentialSource.DEVELOPMENT_PEM,
            )
        }

        var certificateBytes: ByteArray? = null
        var privateKeyBytes: ByteArray? = null
        return try {
            certificateBytes = readSecureFile(certificateFile, MAX_CERTIFICATE_BYTES)
            privateKeyBytes = readSecureFile(privateKeyFile, MAX_PRIVATE_KEY_BYTES)
            val chain = PemCredentialParser.parseCertificates(certificateBytes)
            val privateKey = PemCredentialParser.parsePrivateKey(privateKeyBytes)
            validator.validate(chain, privateKey, HeadUnitCredentialSource.DEVELOPMENT_PEM)
        } catch (error: SecureCredentialFileException) {
            Log.w(LOG_TAG, "Debug credential file rejected: ${error.stage}")
            unavailable(error.code, HeadUnitCredentialSource.DEVELOPMENT_PEM)
        } catch (error: PemCredentialException) {
            unavailable(error.code, HeadUnitCredentialSource.DEVELOPMENT_PEM)
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Debug credential file rejected: unexpected-${error.javaClass.simpleName}")
            unavailable(
                HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                HeadUnitCredentialSource.DEVELOPMENT_PEM,
            )
        } finally {
            certificateBytes?.fill(0)
            privateKeyBytes?.fill(0)
        }
    }

    private fun existsWithoutFollowing(file: File): Boolean? = try {
        Os.lstat(file.absolutePath)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else null
    }

    private fun hasSecureDirectoryPolicy(value: File): Boolean = try {
        val stat = Os.lstat(value.absolutePath)
        val permissionBits = stat.st_mode and ALL_PERMISSION_BITS
        OsConstants.S_ISDIR(stat.st_mode) &&
            stat.st_uid == Process.myUid() &&
            permissionBits == OWNER_DIRECTORY_PERMISSIONS
    } catch (_: Exception) {
        false
    }

    private fun readSecureFile(file: File, maximumBytes: Int): ByteArray {
        if (file.parentFile?.canonicalFile != directory.canonicalFile) {
            throw SecureCredentialFileException(
                HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                "parent",
            )
        }
        val descriptor = try {
            val closeOnExec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                OsConstants.O_CLOEXEC
            } else {
                0
            }
            Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or closeOnExec or OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (_: Exception) {
            throw SecureCredentialFileException(
                HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                "open",
            )
        }

        var material: ByteArray? = null
        val growthProbe = ByteArray(1)
        try {
            val stat = Os.fstat(descriptor)
            val permissionBits = stat.st_mode and ALL_PERMISSION_BITS
            if (
                !OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_uid != Process.myUid() ||
                stat.st_nlink != 1L ||
                permissionBits != OWNER_FILE_PERMISSIONS
            ) {
                throw SecureCredentialFileException(
                    HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                    "metadata",
                )
            }
            if (stat.st_size <= 0L) {
                throw SecureCredentialFileException(
                    HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                    "empty",
                )
            }
            if (stat.st_size > maximumBytes.toLong()) {
                throw SecureCredentialFileException(
                    HeadUnitCredentialCode.MATERIAL_TOO_LARGE,
                    "size",
                )
            }

            val loaded = ByteArray(stat.st_size.toInt())
            material = loaded
            var offset = 0
            while (offset < loaded.size) {
                val count = Os.read(descriptor, loaded, offset, loaded.size - offset)
                if (count <= 0) {
                    throw SecureCredentialFileException(
                        HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                        "short-read",
                    )
                }
                offset += count
            }
            if (Os.read(descriptor, growthProbe, 0, growthProbe.size) > 0) {
                throw SecureCredentialFileException(
                    HeadUnitCredentialCode.MATERIAL_TOO_LARGE,
                    "growth",
                )
            }
            return loaded
        } catch (error: SecureCredentialFileException) {
            material?.fill(0)
            throw error
        } catch (error: Exception) {
            material?.fill(0)
            throw SecureCredentialFileException(
                HeadUnitCredentialCode.FILE_POLICY_VIOLATION,
                "read-${error.javaClass.simpleName}",
            )
        } finally {
            growthProbe.fill(0)
            runCatching { Os.close(descriptor) }
        }
    }

    private class SecureCredentialFileException(
        val code: HeadUnitCredentialCode,
        val stage: String,
    ) : Exception()

    companion object {
        const val DIRECTORY_NAME = "openauto-dev"
        const val CERTIFICATE_FILE_NAME = "chain.pem"
        const val PRIVATE_KEY_FILE_NAME = "private-key.pk8.pem"
        const val MAX_CERTIFICATE_BYTES = 64 * 1024
        const val MAX_PRIVATE_KEY_BYTES = 32 * 1024
        private const val LOG_TAG = "HeadUnitCredential"
        private const val ALL_PERMISSION_BITS = 0x1ff
        private val OWNER_FILE_PERMISSIONS = OsConstants.S_IRUSR or OsConstants.S_IWUSR
        private val OWNER_DIRECTORY_PERMISSIONS =
            OsConstants.S_IRUSR or OsConstants.S_IWUSR or OsConstants.S_IXUSR
    }
}
