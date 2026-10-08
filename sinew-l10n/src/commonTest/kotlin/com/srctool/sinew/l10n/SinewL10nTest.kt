package com.srctool.sinew.l10n

import kotlin.test.Test
import kotlin.test.assertEquals

class SinewL10nTest {
    @Test
    fun artifactNameMatchesModule() {
        assertEquals("sinew-l10n", SinewL10n.ARTIFACT)
    }
}
