package com.srctool.sinew.network

import com.srctool.sinew.testing.FakeNetworkChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeNetworkCheckerTest {
    @Test
    fun aTestSwitchesItByHand() {
        val checker = FakeNetworkChecker()
        assertTrue(checker.isOnline())
        checker.goOffline()
        assertEquals(NetworkStatus.Offline, checker.status.value)
        checker.goOnline()
        assertTrue(checker.isOnline())
        checker.close()
        assertTrue(checker.closed)
    }
}
