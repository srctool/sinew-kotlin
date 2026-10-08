package com.srctool.sinew.testing

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewTestingTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-testing", SinewTesting.ARTIFACT)
    }
}
