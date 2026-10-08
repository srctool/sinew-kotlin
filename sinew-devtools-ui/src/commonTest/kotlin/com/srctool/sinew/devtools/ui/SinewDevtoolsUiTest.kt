package com.srctool.sinew.devtools.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewDevtoolsUiTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-devtools-ui", SinewDevtoolsUi.ARTIFACT)
    }
}
