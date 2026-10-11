package com.srctool.sinew.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The browser reports every network failure the same way, so offline comes from `navigator.onLine`. */
class ConnectivityWasmTest {
    private val fetchFailed = IllegalStateException("Failed to fetch")

    @Test
    fun aFailureWhileOfflineIsNoConnection() = assertTrue(isNoConnection(fetchFailed, online = false))

    @Test
    fun aFailureWhileOnlineIsNot() = assertFalse(isNoConnection(fetchFailed, online = true))

    @Test
    fun anHttpStatusIsNeverNoConnection() = assertFalse(isNoConnection(HttpStatusError(503, null, null), online = false))

    @Test
    fun theBrowserIsOnlineInTests() = assertFalse(isNoConnectionFailure(fetchFailed))
}
