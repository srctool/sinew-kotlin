package com.srctool.sinew.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class SinewExceptionTest {
    private class TestException(
        layer: ExceptionLayer,
        override val errorMessage: ErrorMessage,
        cause: Throwable? = null,
    ) : SinewException("ORD", layer, "PO", cause) {
        override val typeCode = "TST"
    }

    @Test
    fun codeReadsModuleLayerFunctionType() {
        val codes = ExceptionLayer.entries.map { TestException(it, ErrorMessage.Server("x")).code }
        assertEquals(listOf("ORD-VM-PO-TST", "ORD-UC-PO-TST", "ORD-R-PO-TST", "ORD-UT-PO-TST"), codes)
    }

    @Test
    fun messageIsTheServerTextUnchanged() {
        val e = TestException(ExceptionLayer.Repository, ErrorMessage.Server("Pesanan tidak ditemukan."))
        assertEquals("Pesanan tidak ditemukan.", e.message)
    }

    @Test
    fun messageIsTheLocalFallbackWithItsArgumentsFilledIn() {
        val local = ErrorMessage.Local("sinew.error.rateLimited", mapOf("seconds" to 30), "Too many attempts. Try again in {seconds} seconds.")
        assertEquals("Too many attempts. Try again in 30 seconds.", TestException(ExceptionLayer.Repository, local).message)
    }

    @Test
    fun localMessageKeepsItsKeyAndArgumentsForRenderTime() {
        val local = ErrorMessage.Local("sinew.error.noInternet", fallback = "You're offline.")
        val e = TestException(ExceptionLayer.Repository, local)
        assertEquals(local, e.errorMessage)
        assertEquals(emptyMap(), local.args)
    }

    @Test
    fun notRetryableByDefault() {
        assertFalse(TestException(ExceptionLayer.UseCase, ErrorMessage.Server("x")).retryable)
    }

    @Test
    fun keepsItsCause() {
        val cause = IllegalArgumentException("boom")
        assertSame(cause, TestException(ExceptionLayer.Repository, ErrorMessage.Server("x"), cause).cause)
    }

    @Test
    fun envelopeDataMissingIsAPlainErrorNotASinewException() {
        assertIs<IllegalStateException>(EnvelopeDataMissing())
        assertFalse(isSinewException(EnvelopeDataMissing()))
    }

    private fun isSinewException(error: Throwable) = error is SinewException
}
