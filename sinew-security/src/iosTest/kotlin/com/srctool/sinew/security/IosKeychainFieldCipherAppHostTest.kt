package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The contract on CryptoKit with the keyring in the simulator's real Keychain, run as an app by tool/ios-app-test.sh. */
class IosKeychainFieldCipherAppHostTest : FieldCipherContract() {
    private val alias = "sinew.test.field"
    private val store = KeychainRingStore(alias)

    @BeforeTest
    @AfterTest
    fun deleteKeys() = store.delete()

    override suspend fun newCipher(): FieldCipher = FieldCipher.create(PlatformContext(), alias)

    override suspend fun reopen(): FieldCipher = FieldCipher.create(PlatformContext(), alias)

    @Test
    fun theKeyringIsOneKeychainItemUpdatedInPlace() = runTest {
        val cipher = newCipher()
        cipher.encrypt("Alice")
        cipher.rotateKey()
        val ring = assertNotNull(store.load())
        assertEquals(2, ring.keys.size)
    }
}
