package com.srctool.sinew.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

/**
 * The ciphertext layout every target writes: `version 0x01 | key id (4, big-endian) | nonce (12) | ciphertext | tag (16)`.
 * It's Tink's own output with its `TINK` prefix, which the iOS and web implementations write themselves.
 */
internal object CipherLayout {
    const val VERSION: Byte = 0x01
    const val HEADER_SIZE: Int = 5
    const val NONCE_SIZE: Int = 12
    const val TAG_SIZE: Int = 16
    const val MIN_SIZE: Int = HEADER_SIZE + NONCE_SIZE + TAG_SIZE

    fun header(keyId: Int): ByteArray = byteArrayOf(VERSION) + keyId.toBigEndian()

    /** The key id of a value in this layout. */
    fun keyId(value: ByteArray): Int = value.readBigEndian(1)
}

internal fun Int.toBigEndian(): ByteArray = byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte())

internal fun ByteArray.readBigEndian(at: Int): Int =
    ((this[at].toInt() and 0xff) shl 24) or ((this[at + 1].toInt() and 0xff) shl 16) or
        ((this[at + 2].toInt() and 0xff) shl 8) or (this[at + 3].toInt() and 0xff)

/** A platform's keys: AES-256-GCM in [CipherLayout], header included. Safe to call from several coroutines. */
internal interface CipherKeys {
    /** Encrypts with the current key: a fresh nonce from a secure random source, [associatedData] authenticated. */
    suspend fun seal(plain: ByteArray, associatedData: ByteArray): ByteArray

    /** Decrypts with the key the header names. Throws anything when that fails. */
    suspend fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray

    /** Adds a new key and makes it the current one, keeping the others. */
    suspend fun rotate()
}

/** [FieldCipher] over a platform's [CipherKeys], loaded once on first use. */
internal class LayoutFieldCipher(private val load: suspend () -> CipherKeys) : FieldCipher {
    private val loading = Mutex()

    @Volatile
    private var loaded: CipherKeys? = null

    private suspend fun keys(): CipherKeys = loaded ?: loading.withLock { loaded ?: load().also { loaded = it } }

    override suspend fun encrypt(plain: String, context: String): String =
        Base64.encode(keys().seal(plain.encodeToByteArray(), context.encodeToByteArray()))

    override suspend fun decrypt(cipher: String, context: String): String {
        val sealed = try {
            Base64.decode(cipher)
        } catch (e: IllegalArgumentException) {
            throw DecryptionFailed(e)
        }
        if (sealed.size < CipherLayout.MIN_SIZE || sealed[0] != CipherLayout.VERSION) throw DecryptionFailed()
        val keys = keys()                                           // a storage failure stays what it is
        val plain = try {
            keys.open(sealed, context.encodeToByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw if (e is DecryptionFailed) e else DecryptionFailed(e)
        }
        return plain.decodeToString()
    }

    override suspend fun rotateKey() {
        keys().rotate()
    }
}
