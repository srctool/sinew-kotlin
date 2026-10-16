package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** No biometrics on this target: the vault says so, and every call fails clearly. */
class UnavailableBiometricVaultTest {
    private val vault = BiometricVault.create(PlatformContext())

    @Test
    fun itIsUnavailableAndStoresNothing() = runTest {
        assertEquals(BiometricAvailability.Unavailable, vault.availability())
        assertEquals(BiometricStore.Failed(BiometricFailure.Unavailable), vault.store("refresh-token", "Turn on"))
        assertFalse(vault.isStored())
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Unavailable), vault.unlock("Sign in"))
        vault.disable()
    }
}
