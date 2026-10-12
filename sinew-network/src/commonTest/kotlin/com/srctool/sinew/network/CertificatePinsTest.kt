package com.srctool.sinew.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CertificatePinsTest {
    private val valid = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun config(pins: Set<String>) = SinewConfig("https://api.example.com", { "en" }, certificatePins = pins)

    @Test
    fun aWellFormedPinIsAccepted() {
        assertEquals(setOf(valid), config(setOf(valid)).certificatePins)
        assertEquals(emptySet(), config(emptySet()).certificatePins)
    }

    @Test
    fun malformedPinsAreRefusedWhenTheConfigIsBuilt() {
        for (bad in listOf(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // no prefix
            "sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA=", // the wrong hash
            "sha256/AAAA", // not 32 bytes
            "sha256/not base64!", // not Base64
            "sha256/", // empty
        )) {
            val error = assertFailsWith<IllegalArgumentException>(bad) { config(setOf(valid, bad)) }
            assertTrue(bad in error.message.orEmpty(), error.message)
        }
    }
}
