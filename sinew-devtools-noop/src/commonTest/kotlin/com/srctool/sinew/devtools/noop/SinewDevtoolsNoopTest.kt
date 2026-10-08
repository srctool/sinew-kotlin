package com.srctool.sinew.devtools.noop

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewDevtoolsNoopTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-devtools-noop", SinewDevtoolsNoop.ARTIFACT)
    }
}
