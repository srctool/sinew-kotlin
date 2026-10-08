package com.srctool.sinew.models

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewModelsTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-models", SinewModels.ARTIFACT)
    }
}
