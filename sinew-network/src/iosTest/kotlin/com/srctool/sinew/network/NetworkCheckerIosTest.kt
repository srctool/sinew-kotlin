package com.srctool.sinew.network

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** On the simulator: NWPathMonitor reports the host's network, which is online. */
class NetworkCheckerIosTest {
    @Test
    fun theMonitorReportsTheSimulatorsNetwork() = runBlocking {
        val checker = assertIs<IosNetworkChecker>(NetworkChecker.create(PlatformContext()))
        withTimeout(10.seconds) { checker.reported.first { it } }
        assertEquals(NetworkStatus.Online, checker.status.value)
        checker.close()
    }
}
