package com.srctool.sinew.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals

/** How `BiometricManager` and `BiometricPrompt` codes become Sinew's outcomes. */
class BiometricMappingTest {
    @Test
    fun availability() {
        assertEquals(BiometricAvailability.Available, availabilityOf(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(BiometricAvailability.NotEnrolled, availabilityOf(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        for (code in listOf(
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED, BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        )) assertEquals(BiometricAvailability.Unavailable, availabilityOf(code), "$code")
    }

    @Test
    fun promptErrors() {
        for (code in listOf(
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_TIMEOUT,
        )) assertEquals(BiometricFailure.Cancelled, failureOf(code), "$code")
        for (code in listOf(BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT)) {
            assertEquals(BiometricFailure.LockedOut, failureOf(code), "$code")
        }
        for (code in listOf(
            BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
        )) assertEquals(BiometricFailure.Unavailable, failureOf(code), "$code")
    }
}
