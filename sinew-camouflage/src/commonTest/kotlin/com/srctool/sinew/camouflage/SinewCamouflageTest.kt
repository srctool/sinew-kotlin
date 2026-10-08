package com.srctool.sinew.camouflage

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewCamouflageTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-camouflage", SinewCamouflage.ARTIFACT)
    }
}
