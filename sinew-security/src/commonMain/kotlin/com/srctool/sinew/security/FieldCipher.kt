package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext

/**
 * Encrypts individual values (a name, a phone number) before they're stored, with AES-256-GCM from a vetted library:
 * Tink on Android and the JVM, CryptoKit on iOS, Web Crypto in the browser.
 *
 * Every value gets a fresh 96-bit nonce from a secure random source, and [context] is bound as associated data: a
 * ciphertext copied into another row or column fails to decrypt. Every target writes the same layout,
 * `base64(version 0x01 | key id (4) | nonce (12) | ciphertext | tag (16))`, so old values stay readable after
 * [rotateKey].
 *
 * | Target | Where the key lives | Protects against |
 * |---|---|---|
 * | Android | a Tink keyset encrypted by an Android Keystore master key | copying the app's files off the device |
 * | iOS | the Keychain, `AfterFirstUnlockThisDeviceOnly` | copying the app's files off the device |
 * | JVM | a Tink keyset file, owner-only permissions | casual reading only |
 * | wasm | a non-extractable `CryptoKey` in IndexedDB | casual reading only |
 */
public interface FieldCipher {
    /** Encrypts [plain], bound to [context] (for example `"customers.displayName:<rowId>"`), with the current key. */
    public suspend fun encrypt(plain: String, context: String = ""): String

    /**
     * Decrypts what [encrypt] returned for the same [context], with whichever key encrypted it.
     *
     * @throws DecryptionFailed when the value was changed, belongs to another context, or its key is gone.
     */
    public suspend fun decrypt(cipher: String, context: String = ""): String

    /**
     * Makes a new key the current one. New values use it; values encrypted before still decrypt with their own key,
     * and move to the new one when the app next rewrites them.
     */
    public suspend fun rotateKey()

    public companion object {
        /**
         * The platform's cipher for [keyAlias]. Its key is created on first use, so creating it touches no storage.
         * On the JVM the keyset file goes in the user's app-data folder; a desktop app should pass its own
         * [keyAlias], or use the JVM's `create(directory, keyAlias)`.
         */
        public fun create(platform: PlatformContext, keyAlias: String = "sinew.field"): FieldCipher {
            require(keyAlias.isNotBlank()) { "keyAlias must not be blank" }
            return platformFieldCipher(platform, keyAlias)
        }
    }
}

/**
 * A value couldn't be decrypted: it was changed, belongs to another context, or its key is gone (a restore onto
 * another device, a reset keystore). A plain error, not a `SinewException`: the storage guard maps it to
 * `LocalStorageCorruptionException`, and the caller treats the value as absent.
 */
public class DecryptionFailed(cause: Throwable? = null) : IllegalStateException("Value could not be decrypted", cause)

internal expect fun platformFieldCipher(platform: PlatformContext, keyAlias: String): FieldCipher

/** A cipher with one known key and nothing stored: the cross-target fixtures run on it. */
internal expect fun fixedKeyCipher(key: ByteArray, keyId: Int): FieldCipher
