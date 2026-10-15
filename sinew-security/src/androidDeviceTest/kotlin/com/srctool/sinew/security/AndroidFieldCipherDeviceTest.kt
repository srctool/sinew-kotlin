package com.srctool.sinew.security

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.test.runTest
import java.security.KeyStore
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The contract on a device: Tink's keyset in shared preferences, encrypted by a real Android Keystore key. */
class AndroidFieldCipherDeviceTest : FieldCipherContract() {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "sinew.test.field"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun storedKeyset(): String? = context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).getString(alias, null)

    @BeforeTest
    @AfterTest
    fun deleteKeys() {
        context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().remove(alias).commit()
        keyStore().deleteEntry(alias)
    }

    override suspend fun newCipher(): FieldCipher = FieldCipher.create(PlatformContext(context), alias)

    override suspend fun reopen(): FieldCipher = FieldCipher.create(PlatformContext(context), alias)

    @Test
    fun theKeysetIsEncryptedByAKeystoreKey() = runTest {
        newCipher().encrypt("Alice")
        assertTrue(keyStore().containsAlias(alias))
        val keyset = storedKeyset()!!
        assertFalse(keyset.contains("AesGcmKey"), "the keyset is stored encrypted, not in clear text")
    }

    @Test
    fun aLostMasterKeyStartsAFreshKeysetAndOldValuesFail() = runTest {
        val old = newCipher().encrypt("Alice", "ctx")
        val before = storedKeyset()
        keyStore().deleteEntry(alias)                                   // as after a restore onto another device
        val restarted = reopen()
        assertFailsWith<DecryptionFailed> { restarted.decrypt(old, "ctx") }
        val new = restarted.encrypt("Bob", "ctx")
        assertEquals("Bob", reopen().decrypt(new, "ctx"))
        assertNotEquals(before, storedKeyset())
        assertNotEquals(CipherLayout.keyId(Base64.decode(old)), CipherLayout.keyId(Base64.decode(new)))
    }
}
