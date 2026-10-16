package com.srctool.sinew.security

import platform.Security.errSecAuthFailed
import platform.Security.errSecInteractionNotAllowed
import kotlin.test.Test
import kotlin.test.assertEquals

/** How a Keychain read's `OSStatus` becomes a failure, after success, a cancel and a missing item. */
class BiometricStatusTest {
    @Test
    fun statuses() {
        assertEquals(BiometricFailure.Unavailable, failureOfStatus(errSecInteractionNotAllowed))
        assertEquals(BiometricFailure.Cancelled, failureOfStatus(errSecAuthFailed))
    }
}
