package com.srctool.sinew.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewPresentationTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-presentation", SinewPresentation.ARTIFACT)
    }
}
