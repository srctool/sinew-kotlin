package com.srctool.sinew.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixtureTest {
    @Test
    fun aFixtureReadsTheTestResource() {
        assertTrue(fixture("orders_page.json").startsWith("""{"data":[{"id":"o-1""""))
        assertEquals(fixture("orders_page.json"), fixture("orders_page.json"))
    }

    @Test
    fun aMissingFixtureFailsWithItsPath() {
        val error = assertFailsWith<AssertionError> { fixture("missing.json") }
        assertTrue("src/commonTest/resources/missing.json" in error.message.orEmpty(), error.message)
    }
}
