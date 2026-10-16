package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.LocalAuthentication.LAContext
import platform.Security.SecItemCopyMatching
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnAttributes
import platform.Security.kSecUseAuthenticationContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The vault on the simulator's Keychain with simulated Face ID, run as an app by tool/ios-app-test.sh. A test prints
 * `SINEW_FACE …` just before the prompt, and the script answers it.
 *
 * The simulator stores the item with its biometric access control but doesn't enforce it (no Secure Enclave): a read
 * succeeds without a match. So these tests check the item's protection attributes; that no match means no secret, and
 * that a new enrollment invalidates it, can only be shown on a device.
 */
class IosBiometricVaultAppHostTest {
    private val alias = "sinew.test.biometric"

    private fun vault(clearOnReinstall: Boolean = true) = BiometricVault.create(PlatformContext(), alias, clearOnReinstall)

    private fun face(action: String) = println("SINEW_FACE $action")

    @BeforeTest
    @AfterTest
    fun reset() = runBlocking {
        InstallMarker("biometric.$alias").claim()                        // a fresh install is a separate test
        vault().disable()
    }

    @Test
    fun faceIdIsAvailable() = runBlocking {
        assertEquals(BiometricAvailability.Available, vault().availability())
    }

    @Test
    fun aStoredSecretUnlocksAfterAMatch() = runBlocking {
        val vault = vault()
        assertFalse(vault.isStored())
        assertEquals(BiometricStore.Stored, vault.store("refresh-token", "Turn on Face ID"))   // no prompt on iOS
        assertTrue(vault.isStored())
        face("match")
        assertEquals(BiometricUnlock.Unlocked("refresh-token"), vault.unlock("Sign in"))
    }

    @Test
    fun storingAgainReplacesTheSecret() = runBlocking {
        val vault = vault()
        assertEquals(BiometricStore.Stored, vault.store("first-token", "Turn on Face ID"))
        assertEquals(BiometricStore.Stored, vault.store("second-token", "Turn on Face ID"))
        face("match")
        assertEquals(BiometricUnlock.Unlocked("second-token"), vault.unlock("Sign in"))
    }

    @Test
    fun theItemIsProtectedByTheCurrentBiometricsOnThisDeviceOnly() = runBlocking {
        vault().store("refresh-token", "Turn on Face ID")
        val attributes = itemAttributes(alias)
        assertEquals("akpu", attributes["pdmn"], "kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly")
        assertTrue(attributes["accc"].orEmpty().contains("cbio"), "biometryCurrentSet access control: ${attributes["accc"]}")
    }

    @Test
    fun disableDeletesTheSecret() = runBlocking {
        val vault = vault()
        vault.store("refresh-token", "Turn on Face ID")
        vault.disable()
        assertFalse(vault.isStored())
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Invalidated), vault.unlock("Sign in"))
    }

    @Test
    fun aReinstallFindsNoSecret() = runBlocking {
        vault().store("refresh-token", "Turn on Face ID")
        InstallMarker("biometric.$alias").remove()                       // what an uninstall does
        assertFalse(vault().isStored())
    }

    @Test
    fun withoutClearOnReinstallTheSecretSurvives() = runBlocking {
        vault().store("refresh-token", "Turn on Face ID")
        InstallMarker("biometric.$alias").remove()
        assertTrue(vault(clearOnReinstall = false).isStored())
    }
}

/** The vault item's attributes as text (`pdmn`: protection class, `accc`: access control), read without a prompt. */
@OptIn(ExperimentalForeignApi::class)
private fun itemAttributes(service: String): Map<String, String> {
    val context = LAContext().apply { interactionNotAllowed = true }
    return withDictionary({ query ->
        query.put(kSecClass, kSecClassGenericPassword)
        query.putBridged(kSecAttrService, service)
        query.putBridged(kSecAttrAccount, "secret")
        query.put(kSecReturnAttributes, kCFBooleanTrue)
        query.putBridged(kSecUseAuthenticationContext, context)
    }) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            check(SecItemCopyMatching(query, result.ptr) == errSecSuccess) { "no vault item" }
            val attributes = CFBridgingRelease(result.value) as Map<*, *>
            attributes.entries.associate { (key, value) -> key.toString() to value.toString() }
        }
    }
}
