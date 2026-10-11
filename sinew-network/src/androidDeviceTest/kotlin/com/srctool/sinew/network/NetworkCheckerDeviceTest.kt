package com.srctool.sinew.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** On a real device or emulator: the checker follows the default network as Wi-Fi and data go off and on. */
@RunWith(AndroidJUnit4::class)
class NetworkCheckerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val checker = NetworkChecker.create(PlatformContext(instrumentation.targetContext))

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun radios(on: Boolean) {
        val state = if (on) "enable" else "disable"
        shell("svc wifi $state")
        shell("svc data $state")
    }

    @After
    fun restore() {
        radios(on = true)
        checker.close()
    }

    @Test
    fun followsTheDefaultNetwork() = runBlocking {
        withTimeout(60.seconds) { checker.status.first { it == NetworkStatus.Online } }

        radios(on = false)
        withTimeout(30.seconds) { checker.status.first { it == NetworkStatus.Offline } }
        assertEquals(false, checker.isOnline())

        radios(on = true)
        withTimeout(90.seconds) { checker.status.first { it == NetworkStatus.Online } }   // validation takes a while
        assertEquals(true, checker.isOnline())
    }

    @Test
    fun closeTwiceIsHarmless() {
        checker.close()
        checker.close()
    }
}
