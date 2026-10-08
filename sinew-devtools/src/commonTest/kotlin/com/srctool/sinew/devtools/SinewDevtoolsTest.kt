package com.srctool.sinew.devtools

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewDevtoolsTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-devtools", SinewDevtools.ARTIFACT)
    }
}
