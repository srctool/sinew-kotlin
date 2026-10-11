package com.srctool.sinew.network

import com.srctool.sinew.models.PlatformContext
import kotlin.test.Test
import kotlin.test.assertEquals

private fun dispatchWindowEvent(type: String): Unit = js("window.dispatchEvent(new Event(type))")

/** In the browser: navigator.onLine at start, then the window's online and offline events. */
class NetworkCheckerWasmTest {
    @Test
    fun followsTheWindowsEvents() {
        val checker = NetworkChecker.create(PlatformContext())
        assertEquals(NetworkStatus.Online, checker.status.value)                   // the test browser is online
        dispatchWindowEvent("offline")
        assertEquals(NetworkStatus.Offline, checker.status.value)
        dispatchWindowEvent("online")
        assertEquals(NetworkStatus.Online, checker.status.value)

        checker.close()
        dispatchWindowEvent("offline")
        assertEquals(NetworkStatus.Online, checker.status.value)                   // closed: listeners removed
    }
}
