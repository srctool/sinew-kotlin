package com.srctool.sinew.network

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewNetworkTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-network", SinewNetwork.ARTIFACT)
    }
}
