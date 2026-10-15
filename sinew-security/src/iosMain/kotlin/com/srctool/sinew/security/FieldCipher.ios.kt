package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

internal actual fun platformFieldCipher(platform: PlatformContext, keyAlias: String): FieldCipher =
    LayoutFieldCipher { RingKeys.open(CryptoKitAesGcm, KeychainRingStore(keyAlias)) }

internal actual fun fixedKeyCipher(key: ByteArray, keyId: Int): FieldCipher =
    LayoutFieldCipher { RingKeys.fixed(CryptoKitAesGcm, keyId, key.copyOf()) }

/** AES-256-GCM through cryptography-kotlin's CryptoKit provider; it draws each nonce from the system's secure random. */
internal object CryptoKitAesGcm : RingCrypto<ByteArray> {
    private val gcm = CryptographyProvider.CryptoKit.get(AES.GCM)

    override suspend fun newKey(): ByteArray = gcm.keyGenerator(AES.Key.Size.B256).generateKey().encodeToByteArray(AES.Key.Format.RAW)

    override fun newKeyId(): Int = CryptographyRandom.nextInt()

    override suspend fun seal(key: ByteArray, plain: ByteArray, associatedData: ByteArray): ByteArray =
        decode(key).cipher().encrypt(plain, associatedData)

    override suspend fun open(key: ByteArray, body: ByteArray, associatedData: ByteArray): ByteArray =
        decode(key).cipher().decrypt(body, associatedData)

    private suspend fun decode(key: ByteArray) = gcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
}

/**
 * The keyring in one Keychain generic-password item (`service = keyAlias`, `account = "keyring"`), readable after the
 * first unlock, on this device only: it isn't backed up or moved to another device.
 *
 * Layout: `primary id (4) | (key id (4) | key (32))*`, big-endian ids.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainRingStore(private val service: String) : RingStore<ByteArray> {
    override suspend fun load(): Keyring<ByteArray>? {
        val data = withQuery(returnData = true) { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                when (val status = SecItemCopyMatching(query, result.ptr)) {
                    errSecSuccess -> CFBridgingRelease(result.value) as NSData
                    errSecItemNotFound -> null
                    else -> throw KeychainException("read", status)
                }
            }
        } ?: return null
        return decodeRing(data.toByteArray())
    }

    /** Updates the item in place, or adds it: the keyring is never absent between two writes. */
    override suspend fun save(ring: Keyring<ByteArray>) {
        val data = encodeRing(ring).toNSData()
        val updated = withQuery { query -> withDictionary({ it.put(kSecValueData, data) }) { SecItemUpdate(query, it) } }
        val status = if (updated == errSecItemNotFound) withQuery(value = data) { SecItemAdd(it, null) } else updated
        if (status != errSecSuccess) throw KeychainException("write", status)
    }

    /** Deletes the item: tests start from an empty Keychain. */
    fun delete() {
        withQuery { SecItemDelete(it) }
    }

    private fun <T> withQuery(value: NSData? = null, returnData: Boolean = false, block: (CFDictionaryRef?) -> T): T =
        withDictionary({ query ->
            query.put(kSecClass, kSecClassGenericPassword)
            query.put(kSecAttrService, service)
            query.put(kSecAttrAccount, "keyring")
            if (value != null) {
                query.put(kSecValueData, value)
                query.put(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            }
            if (returnData) {
                query.put(kSecReturnData, kCFBooleanTrue)
                query.put(kSecMatchLimit, kSecMatchLimitOne)
            }
        }, block)
}

/** A CoreFoundation dictionary being filled: Kotlin values are bridged, and released with the dictionary. */
@OptIn(ExperimentalForeignApi::class)
private class CFQuery(val dictionary: CFMutableDictionaryRef?) {
    val bridged = mutableListOf<CFTypeRef?>()

    fun put(key: CFStringRef?, value: CFTypeRef?) = CFDictionaryAddValue(dictionary, key, value)

    fun put(key: CFStringRef?, value: Any) {
        val ref = CFBridgingRetain(value)
        bridged += ref
        CFDictionaryAddValue(dictionary, key, ref)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun <T> withDictionary(fill: (CFQuery) -> Unit, block: (CFDictionaryRef?) -> T): T {
    val query = CFQuery(CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    try {
        fill(query)
        return block(query.dictionary)
    } finally {
        query.bridged.forEach { CFRelease(it) }
        CFRelease(query.dictionary)
    }
}

/** A Keychain call failed with [status], an `OSStatus`. */
internal class KeychainException(operation: String, val status: Int) : IllegalStateException("Keychain $operation failed: $status")

private const val KEY_SIZE = 32

internal fun encodeRing(ring: Keyring<ByteArray>): ByteArray {
    var out = ring.primary.toBigEndian()
    for ((id, key) in ring.keys) {
        require(key.size == KEY_SIZE) { "a key is $KEY_SIZE bytes" }
        out += id.toBigEndian() + key
    }
    return out
}

/** The keyring in [bytes], or `null` when they aren't one (the item is then replaced, and its values are lost). */
internal fun decodeRing(bytes: ByteArray): Keyring<ByteArray>? {
    if (bytes.size < 4 + 4 + KEY_SIZE || (bytes.size - 4) % (4 + KEY_SIZE) != 0) return null
    val keys = (4 until bytes.size step 4 + KEY_SIZE).associate { at ->
        bytes.readBigEndian(at) to bytes.copyOfRange(at + 4, at + 4 + KEY_SIZE)
    }
    val primary = bytes.readBigEndian(0)
    return if (primary in keys) Keyring(primary, keys) else null
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { bytes ->
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
