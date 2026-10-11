package com.srctool.sinew.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** A network counts as online only with internet and Android's validation: a captive portal isn't. */
class NetworkStatusTest {
    @Test
    fun onlineNeedsInternetAndValidation() {
        assertEquals(NetworkStatus.Online, networkStatus(hasInternet = true, validated = true))
        assertEquals(NetworkStatus.Offline, networkStatus(hasInternet = true, validated = false))
        assertEquals(NetworkStatus.Offline, networkStatus(hasInternet = false, validated = true))
        assertEquals(NetworkStatus.Offline, networkStatus(hasInternet = false, validated = false))
    }
}
