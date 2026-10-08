package com.srctool.sinew.security

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewSecurityTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-security", SinewSecurity.ARTIFACT)
    }
}
