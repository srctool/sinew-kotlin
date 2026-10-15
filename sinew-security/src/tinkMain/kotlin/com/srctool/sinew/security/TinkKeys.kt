package com.srctool.sinew.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.util.SecretBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** A Tink keyset and how to rotate it. */
internal interface TinkKeyset {
    val handle: KeysetHandle

    fun rotate()
}

/** [CipherKeys] on Tink's `Aead`, whose output with the `TINK` prefix is [CipherLayout]. */
internal class TinkKeys(private val keyset: TinkKeyset) : CipherKeys {
    @Volatile
    private var aead: Aead = keyset.handle.aead()

    override suspend fun seal(plain: ByteArray, associatedData: ByteArray): ByteArray = aead.encrypt(plain, associatedData)

    override suspend fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray = aead.decrypt(sealed, associatedData)

    override suspend fun rotate() {
        withContext(Dispatchers.IO) {
            synchronized(keyset) {
                keyset.rotate()
                aead = keyset.handle.aead()
            }
        }
    }
}

/** Registers Tink's AEAD key managers, which creating a key and building an `Aead` both need. Idempotent. */
internal fun registerAead() {
    AeadConfig.register()
}

internal fun KeysetHandle.aead(): Aead {
    registerAead()
    return getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

/** A new keyset with one AES-256-GCM key. */
internal fun newKeyset(): KeysetHandle {
    registerAead()
    return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
}

/** A keyset handle with a new AES-256-GCM key, as the primary, added to [this]. */
internal fun KeysetHandle.withNewPrimary(): KeysetHandle {
    registerAead()
    return KeysetHandle.newBuilder(this)
        .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM).withRandomId().makePrimary())
        .build()
}

/** A keyset in memory, with nothing stored. */
internal class MemoryKeyset(override var handle: KeysetHandle) : TinkKeyset {
    override fun rotate() {
        handle = handle.withNewPrimary()
    }
}

internal actual fun fixedKeyCipher(key: ByteArray, keyId: Int): FieldCipher = LayoutFieldCipher {
    val imported = AesGcmKey.builder()
        .setParameters(
            AesGcmParameters.builder()
                .setKeySizeBytes(32).setIvSizeBytes(12).setTagSizeBytes(16)
                .setVariant(AesGcmParameters.Variant.TINK).build(),
        )
        .setKeyBytes(SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get()))
        .setIdRequirement(keyId)
        .build()
    TinkKeys(MemoryKeyset(KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(imported).makePrimary()).build()))
}
