package com.srctool.sinew.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BiometricOutcomeTest {
    @Test
    fun anUnlockedOutcomeNeverPrintsTheSecret() {
        val outcome = BiometricUnlock.Unlocked("refresh-token")
        assertFalse("refresh-token" in outcome.toString())
        assertFalse("refresh-token" in listOf(outcome).toString())
    }

    @Test
    fun unlockedOutcomesCompareBySecret() {
        assertEquals(BiometricUnlock.Unlocked("a"), BiometricUnlock.Unlocked("a"))
        assertEquals(BiometricUnlock.Unlocked("a").hashCode(), BiometricUnlock.Unlocked("a").hashCode())
        assertFalse(BiometricUnlock.Unlocked("a") == BiometricUnlock.Unlocked("b"))
    }
}
