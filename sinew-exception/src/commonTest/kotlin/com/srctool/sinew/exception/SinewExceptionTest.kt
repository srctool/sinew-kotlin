package com.srctool.sinew.exception

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewExceptionTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-exception", SinewException.ARTIFACT)
    }
}
