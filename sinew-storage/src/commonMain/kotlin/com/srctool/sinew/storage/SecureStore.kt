package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import com.srctool.sinew.security.DecryptionFailed
import com.srctool.sinew.security.FieldCipher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small secrets: the refresh token, a data key, a device id. Never large data or lists, which go in the app's
 * database with `FieldCipher`.
 *
 * Every target uses the same store: a dedicated [KeyValueStore] (`<namespace>_secure`) whose values a
 * [FieldCipher] (`sinew.secure.<namespace>`) encrypts, with the namespace and key bound as associated data, so a
 * value copied to another key doesn't decrypt. The cipher's key lives in the platform's key storage (the Android
 * Keystore, the iOS Keychain this-device-only, a desktop key file, a non-extractable Web Crypto key).
 *
 * - **A value that can't be decrypted is absent**, and is deleted: after a restore onto another device, a reset
 *   keystore, or tampering. For a session, absent means signed out.
 * - **A reinstall starts empty** on every target: the data file goes with the app, even where the key survives (the
 *   iOS Keychain).
 * - Writes are serialized, so the last one wins.
 */
public interface SecureStore {
    public suspend fun read(key: String): String?

    public suspend fun write(key: String, value: String)

    public suspend fun delete(key: String)

    /** Deletes every secret of this store: call it on sign-out. */
    public suspend fun clearUserData()

    public companion object {
        /** The store for [namespace]; on the JVM, `create(directory, namespace)` chooses the folder. */
        public fun create(platform: PlatformContext, namespace: String = "sinew"): SecureStore {
            requireNamespace(namespace)
            return EncryptedSecureStore(
                namespace,
                KeyValueStore.create(platform, "${namespace}_secure"),
                FieldCipher.create(platform, "sinew.secure.$namespace"),
            )
        }
    }
}

internal fun requireNamespace(namespace: String) {
    require(namespace.isNotBlank() && namespace.none { it == '/' || it == '\\' }) { "namespace must be a plain, non-blank name" }
}

/** [SecureStore] as [cipher]-encrypted values in [values]. */
internal class EncryptedSecureStore(
    private val namespace: String,
    private val values: KeyValueStore,
    private val cipher: FieldCipher,
) : SecureStore {
    private val writes = Mutex()

    private fun context(key: String) = "$namespace:$key"

    override suspend fun read(key: String): String? {
        val sealed = values.getString(key) ?: return null
        return try {
            cipher.decrypt(sealed, context(key))
        } catch (_: DecryptionFailed) {
            writes.withLock { if (values.getString(key) == sealed) values.remove(key) }   // fail closed, and tidy up
            null
        }
    }

    override suspend fun write(key: String, value: String) {
        writes.withLock { values.setString(key, cipher.encrypt(value, context(key))) }
    }

    override suspend fun delete(key: String) {
        writes.withLock { values.remove(key) }
    }

    override suspend fun clearUserData() {
        writes.withLock { values.clear() }
    }
}
