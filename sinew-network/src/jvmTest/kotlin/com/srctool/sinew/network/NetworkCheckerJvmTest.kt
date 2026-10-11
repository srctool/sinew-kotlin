package com.srctool.sinew.network

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class NetworkCheckerJvmTest {
    /**
     * The poller runs on a clock of its own, advanced by hand. Not `runTest`'s: `runTest` drains its clock when it
     * ends, and a poller that `close()` failed to stop would keep scheduling its next tick, so a broken checker would
     * hang the test instead of failing it.
     */
    @Test
    fun itChecksTheInterfacesEveryFiveSeconds() {
        val clock = TestCoroutineScheduler()
        var up = true
        var checks = 0
        val checker = JvmNetworkChecker({ checks++; up }, 5.seconds, StandardTestDispatcher(clock))
        try {
            clock.runCurrent()
            assertEquals(NetworkStatus.Online, checker.status.value)
            assertEquals(1, checks)

            up = false
            clock.advanceTimeBy(4_999)
            clock.runCurrent()
            assertEquals(NetworkStatus.Online, checker.status.value)               // not checked yet
            clock.advanceTimeBy(2)
            clock.runCurrent()
            assertEquals(NetworkStatus.Offline, checker.status.value)
            assertEquals(false, checker.isOnline())

            checker.close()
            up = true
            clock.advanceTimeBy(60_000)
            clock.runCurrent()
            assertEquals(NetworkStatus.Offline, checker.status.value)              // closed: no more checks
            assertEquals(2, checks)
        } finally {
            checker.close()
        }
    }

    @Test
    fun createGivesTheJvmChecker() {
        val checker = NetworkChecker.create(PlatformContext())
        assertIs<JvmNetworkChecker>(checker)
        checker.close()
    }
}


