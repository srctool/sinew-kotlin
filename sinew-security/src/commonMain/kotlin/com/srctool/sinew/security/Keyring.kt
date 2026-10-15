package com.srctool.sinew.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/** The keys of one alias where there's no Tink: [primary] encrypts, every key in [keys] decrypts. */
internal class Keyring<K>(val primary: Int, val keys: Map<Int, K>) {
    init {
        require(primary in keys) { "the primary key must be in the keyring" }
    }
}

/** AES-256-GCM from the platform's vetted crypto, over keys of type [K]. */
internal interface RingCrypto<K> {
    /** A new random 256-bit key. */
    suspend fun newKey(): K

    /** A random key id, from a secure random source. */
    fun newKeyId(): Int

    /** `nonce (12) | ciphertext | tag (16)`, the nonce fresh from a secure random source. */
    suspend fun seal(key: K, plain: ByteArray, associatedData: ByteArray): ByteArray

    /** Opens what [seal] returned. Throws when the tag doesn't match. */
    suspend fun open(key: K, body: ByteArray, associatedData: ByteArray): ByteArray
}

/** Where a [Keyring] is kept. */
internal interface RingStore<K> {
    /** The stored keyring, or `null` when there's none yet. */
    suspend fun load(): Keyring<K>?

    suspend fun save(ring: Keyring<K>)
}

/** [CipherKeys] on a [Keyring]: the header is written here, the rest by [crypto]. */
internal class RingKeys<K> private constructor(
    private val crypto: RingCrypto<K>,
    private val store: RingStore<K>,
    ring: Keyring<K>,
) : CipherKeys {
    private val rotating = Mutex()

    @Volatile
    private var ring: Keyring<K> = ring

    override suspend fun seal(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val ring = ring
        return CipherLayout.header(ring.primary) + crypto.seal(ring.keys.getValue(ring.primary), plain, associatedData)
    }

    override suspend fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        val key = ring.keys[CipherLayout.keyId(sealed)] ?: throw DecryptionFailed()
        return crypto.open(key, sealed.copyOfRange(CipherLayout.HEADER_SIZE, sealed.size), associatedData)
    }

    override suspend fun rotate() {
        rotating.withLock {
            val next = ring.adding(crypto)
            store.save(next)
            ring = next
        }
    }

    companion object {
        /** The stored keyring, or a new one with a single key, saved before first use. */
        suspend fun <K> open(crypto: RingCrypto<K>, store: RingStore<K>): RingKeys<K> {
            val ring = store.load() ?: run {
                val id = crypto.newKeyId()
                Keyring(id, mapOf(id to crypto.newKey())).also { store.save(it) }
            }
            return RingKeys(crypto, store, ring)
        }

        /** A keyring that isn't stored, with a known key: for tests and the cross-target fixtures. */
        fun <K> fixed(crypto: RingCrypto<K>, keyId: Int, key: K): RingKeys<K> =
            RingKeys(crypto, NoStore(), Keyring(keyId, mapOf(keyId to key)))
    }
}

/** A keyring with a new key as the primary, its id unused in this keyring. */
private suspend fun <K> Keyring<K>.adding(crypto: RingCrypto<K>): Keyring<K> {
    var id: Int
    do {
        id = crypto.newKeyId()
    } while (id in keys)
    return Keyring(id, keys + (id to crypto.newKey()))
}

private class NoStore<K> : RingStore<K> {
    override suspend fun load(): Keyring<K>? = null
    override suspend fun save(ring: Keyring<K>) {}
}
