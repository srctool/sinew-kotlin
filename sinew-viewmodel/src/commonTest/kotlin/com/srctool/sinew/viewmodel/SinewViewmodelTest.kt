package com.srctool.sinew.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewViewmodelTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-viewmodel", SinewViewmodel.ARTIFACT)
    }
}
