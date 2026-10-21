package com.srctool.sinew.testing

import com.srctool.sinew.security.BiometricAvailability
import com.srctool.sinew.security.BiometricFailure
import com.srctool.sinew.security.BiometricStore
import com.srctool.sinew.security.BiometricUnlock
import com.srctool.sinew.security.DecryptionFailed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityFakesTest {
    @Test
    fun fieldCipherStoresAReadableMarkedForm() = runTest {
        val cipher = FakeFieldCipher()
        assertEquals("enc(Alice)", cipher.encrypt("Alice"))
        assertEquals("enc[customers.name:1](Alice)", cipher.encrypt("Alice", "customers.name:1"))
        assertEquals("Alice", cipher.decrypt("enc(Alice)"))
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
        assertEquals("a)b", cipher.decrypt(cipher.encrypt("a)b", "ctx"), "ctx"))
    }

    @Test
    fun fieldCipherRefusesAnotherContextOrAnUnmarkedValue() = runTest {
        val cipher = FakeFieldCipher()
        val sealed = cipher.encrypt("Alice", "customers.name:1")
        assertFailsWith<DecryptionFailed> { cipher.decrypt(sealed, "customers.name:2") }
        assertFailsWith<DecryptionFailed> { cipher.decrypt(sealed) }
        assertFailsWith<DecryptionFailed> { cipher.decrypt("Alice") }
        assertFailsWith<DecryptionFailed> { cipher.decrypt("enc(Alice") }
    }

    @Test
    fun fieldCipherFailsTheNextDecryptOnce() = runTest {
        val cipher = FakeFieldCipher()
        val sealed = cipher.encrypt("Alice")
        cipher.failNextDecrypt()
        assertFailsWith<DecryptionFailed> { cipher.decrypt(sealed) }
        assertEquals("Alice", cipher.decrypt(sealed))
    }

    @Test
    fun fieldCipherCountsRotations() = runTest {
        val cipher = FakeFieldCipher()
        cipher.rotateKey()
        cipher.rotateKey()
        assertEquals(2, cipher.rotations)
    }

    @Test
    fun vaultStoresAndUnlocksRecordingEachPrompt() = runTest {
        val vault = FakeBiometricVault()
        assertFalse(vault.isStored())
        assertEquals(BiometricStore.Stored, vault.store("refresh-token", "Turn on"))
        assertTrue(vault.isStored())
        assertEquals(BiometricUnlock.Unlocked("refresh-token"), vault.unlock("Sign in"))
        assertEquals(listOf("Turn on", "Sign in"), vault.prompts)
        assertEquals("refresh-token", vault.storedSecret)
    }

    @Test
    fun vaultReportsTheAvailabilitySetAndFailsWithoutBiometrics() = runTest {
        val vault = FakeBiometricVault(BiometricAvailability.NotEnrolled)
        assertEquals(BiometricAvailability.NotEnrolled, vault.availability())
        assertEquals(BiometricStore.Failed(BiometricFailure.Unavailable), vault.store("t", "Turn on"))
        vault.availability = BiometricAvailability.Available
        vault.store("t", "Turn on")
        vault.availability = BiometricAvailability.Unavailable
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Unavailable), vault.unlock("Sign in"))
        assertEquals(listOf("Turn on"), vault.prompts)
    }

    @Test
    fun vaultFailsScriptedStoresAndUnlocksInOrder() = runTest {
        val vault = FakeBiometricVault()
        vault.nextStore(BiometricFailure.Cancelled)
        assertEquals(BiometricStore.Failed(BiometricFailure.Cancelled), vault.store("t", "Turn on"))
        assertFalse(vault.isStored())
        vault.store("t", "Turn on")
        vault.nextUnlock(BiometricFailure.Cancelled)
        vault.nextUnlock(BiometricFailure.LockedOut)
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Cancelled), vault.unlock("Sign in"))
        assertEquals(BiometricUnlock.Failed(BiometricFailure.LockedOut), vault.unlock("Sign in"))
        assertEquals(BiometricUnlock.Unlocked("t"), vault.unlock("Sign in"))
    }

    @Test
    fun aFailedStoreClearsAnEarlierSecret() = runTest {
        val vault = FakeBiometricVault()
        vault.store("old", "Turn on")
        vault.nextStore(BiometricFailure.Cancelled)
        vault.store("new", "Turn on")
        assertFalse(vault.isStored())
    }

    @Test
    fun nothingStoredOrInvalidatedUnlocksAsInvalidated() = runTest {
        val vault = FakeBiometricVault()
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Invalidated), vault.unlock("Sign in"))
        vault.store("t", "Turn on")
        vault.invalidate()
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Invalidated), vault.unlock("Sign in"))
        vault.store("t", "Turn on")
        vault.disable()
        assertFalse(vault.isStored())
        assertEquals(listOf("Turn on", "Turn on"), vault.prompts)
    }
}
