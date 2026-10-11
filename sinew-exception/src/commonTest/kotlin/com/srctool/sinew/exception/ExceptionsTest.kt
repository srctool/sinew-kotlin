package com.srctool.sinew.exception

import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer.Repository
import com.srctool.sinew.models.SinewException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ExceptionsTest {
    private val builtIns: List<SinewException> = listOf(
        NoInternetConnectionException("ORD", Repository, "GO"),
        RequestTimeOutException("ORD", Repository, "GO"),
        ValidationException("ORD", Repository, "GO"),
        UnauthorizedException("ORD", Repository, "GO"),
        ForbiddenException("ORD", Repository, "GO"),
        NotFoundException("ORD", Repository, "GO"),
        ConflictException("ORD", Repository, "GO"),
        RateLimitedException("ORD", Repository, "GO", retryAfter = 30.seconds),
        ServerException("ORD", Repository, "GO", status = 500),
        ServiceUnavailableException("ORD", Repository, "GO", status = 503),
        ApiErrorException("ORD", Repository, "GO", ErrorMessage.Server("Out of stock.")),
        UndefinedErrorResponseException("ORD", Repository, "GO", status = 418),
        ParseException("ORD", Repository, "GO"),
        LocalStorageCorruptionException("ORD", Repository, "GO"),
        StorageFullException("ORD", Repository, "GO"),
        StorageReadException("ORD", Repository, "GO"),
        StorageWriteException("ORD", Repository, "GO"),
        PollingTimeOutException("ORD", Repository, "GO"),
        UnexpectedException("ORD", Repository, "GO"),
    )

    private val sinewKeys = setOf(
        "noInternet", "timeout", "validation", "unauthorized", "forbidden", "notFound", "conflict", "rateLimited",
        "rateLimitedNoTime", "server", "unavailable", "generic", "storageFull", "stillProcessing",
    ).map { "sinew.error.$it" }.toSet()

    @Test
    fun everyBuiltInTypeHasItsOwnCode() {
        val codes = builtIns.map { it.typeCode }
        assertEquals(19, codes.toSet().size)
        assertEquals(
            setOf("NIC", "RTO", "VAL", "UNA", "FOR", "NTF", "CFL", "RTL", "SRV", "SUN", "AE", "UER", "DF", "LSC", "SF", "SR", "SW", "PTO", "GEN"),
            codes.toSet(),
        )
    }

    @Test
    fun everyLocalMessageUsesAReservedSinewKey() {
        val keys = builtIns.mapNotNull { (it.errorMessage as? ErrorMessage.Local)?.key }
        assertEquals(18, keys.size)                               // all but ApiErrorException, which is always server text
        assertTrue(keys.all { it in sinewKeys }, "unknown keys: ${keys - sinewKeys}")
    }

    @Test
    fun everyFallbackIsPlainTextWithItsArgumentsFilledIn() {
        builtIns.filter { it.errorMessage is ErrorMessage.Local }.forEach {
            val text = it.message
            assertTrue(text.isNotBlank() && text.endsWith("."), "${it.typeCode}: $text")
            assertFalse(listOf("{", "Exception", "null", "http").any { word -> word in text }, "${it.typeCode}: $text")
        }
    }

    @Test
    fun retryableFollowsTheClassification() {
        assertEquals(setOf("NIC", "RTO", "RTL", "SUN", "SR", "SW", "PTO"), builtIns.filter { it.retryable }.map { it.typeCode }.toSet())
    }

    @Test
    fun codesReadModuleLayerFunctionType() {
        assertEquals("ORD-R-GO-NIC", builtIns.first().code)
    }

    @Test
    fun serverIfReadableTypesTakeTheBackendText() {
        val e = NotFoundException("ORD", Repository, "GO", ErrorMessage.Server("Pesanan tidak ditemukan."))
        assertEquals(ErrorMessage.Server("Pesanan tidak ditemukan."), e.errorMessage)
        assertIs<ErrorMessage.Local>(NotFoundException("ORD", Repository, "GO").errorMessage)
    }

    @Test
    fun rateLimitedNamesTheWaitOnlyWhenItIsKnown() {
        val known = RateLimitedException("ORD", Repository, "GO", retryAfter = 30.seconds).errorMessage as ErrorMessage.Local
        assertEquals("sinew.error.rateLimited", known.key)
        assertEquals(mapOf<String, Any>("seconds" to 30L), known.args)
        val unknown = RateLimitedException("ORD", Repository, "GO", retryAfter = null)
        assertEquals("sinew.error.rateLimitedNoTime", (unknown.errorMessage as ErrorMessage.Local).key)
        assertNull(unknown.retryAfter)
    }

    @Test
    fun extraFieldsAreKept() {
        val validation = ValidationException("ORD", Repository, "GO", fieldErrors = mapOf("email" to listOf("Email is taken.")))
        assertEquals(mapOf("email" to listOf("Email is taken.")), validation.fieldErrors)
        assertEquals(502, ServerException("ORD", Repository, "GO", status = 502).status)
        assertEquals(503, ServiceUnavailableException("ORD", Repository, "GO", status = 503).status)
        assertEquals(418, UndefinedErrorResponseException("ORD", Repository, "GO", status = 418).status)
    }
}
