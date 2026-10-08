package com.srctool.sinew.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewStorageTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-storage", SinewStorage.ARTIFACT)
    }
}
