package com.srctool.sinew.paging

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewPagingTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-paging", SinewPaging.ARTIFACT)
    }
}
