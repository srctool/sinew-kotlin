package com.srctool.sinew.security

import android.content.Context
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

/** The shared-preferences file holding every alias's encrypted keyset. Exclude it from backup. */
internal const val KEYSET_PREFS: String = "sinew.security.keysets"

internal actual fun platformFieldCipher(platform: PlatformContext, keyAlias: String): FieldCipher {
    val context = platform.context.applicationContext
    return LayoutFieldCipher { withContext(Dispatchers.IO) { TinkKeys(AndroidKeyset.open(context, keyAlias)) } }
}

/**
 * A Tink keyset in [KEYSET_PREFS], encrypted by the Android Keystore key `[keyAlias]` (StrongBox when Tink finds it
 * usable). Tink keeps the keyset in clear text, still private to the app, on a device whose Keystore fails its self
 * test.
 */
internal class AndroidKeyset private constructor(private val manager: AndroidKeysetManager) : TinkKeyset {
    override val handle: KeysetHandle get() = manager.keysetHandle

    override fun rotate() {
        manager.add(TEMPLATE)
        val handle = manager.keysetHandle
        manager.setPrimary(handle.getAt(handle.size() - 1).id)
    }

    companion object {
        private val TEMPLATE: KeyTemplate = KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM)

        /**
         * The alias's keyset, created on first use. When the Keystore master key is lost or unusable (a restore onto
         * another device, a reset keystore), Tink can't read the keyset and throws: the keyset and the master key are
         * then deleted and a fresh pair is made, and every old value throws [DecryptionFailed].
         */
        fun open(context: Context, keyAlias: String): AndroidKeyset {
            registerAead()                                      // outside the try: its failure must not delete keys
            return try {
                AndroidKeyset(build(context, keyAlias))
            } catch (_: GeneralSecurityException) {
                AndroidKeyset(rebuild(context, keyAlias))
            } catch (_: IOException) {
                AndroidKeyset(rebuild(context, keyAlias))
            }
        }

        private fun rebuild(context: Context, keyAlias: String): AndroidKeysetManager {
            context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().remove(keyAlias).commit()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
            return build(context, keyAlias)
        }

        private fun build(context: Context, keyAlias: String): AndroidKeysetManager = AndroidKeysetManager.Builder()
            .withSharedPref(context, keyAlias, KEYSET_PREFS)
            .withKeyTemplate(TEMPLATE)
            .withMasterKeyUri("android-keystore://$keyAlias")
            .build()
    }
}
