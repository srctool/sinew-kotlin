package com.srctool.sinew.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StateTest {
    private class TestException : SinewException("ORD", ExceptionLayer.Repository, "GO") {
        override val typeCode = "TST"
        override val errorMessage = ErrorMessage.Local("test.error", fallback = "Test failure.")
    }

    private val error = TestException()

    // Paging shapes

    @Test
    fun pagingDefaultsAreAnEmptyPageWithNoNextPage() {
        assertEquals(PagingDomain(emptyList(), PagingMetaDomain(0, 0, 0, 0, false, false, "")), PagingDomain<String>())
    }

    @Test
    fun storedPageMapsItsItemsAndEveryMetaField() {
        val meta = PagingMetaEntity(page = 2, limit = 10, total = 35, totalPage = 4, hasNextPage = true, hasPreviousPage = true, nextKey = "c2")
        val domain = PagingEntity(listOf(1, 2), meta).toDomain { "item $it" }
        assertEquals(PagingDomain(listOf("item 1", "item 2"), PagingMetaDomain(2, 10, 35, 4, true, true, "c2")), domain)
    }

    // ViewState

    @Test
    fun dataOrNullReadsEveryVariant() {
        assertNull(ViewState.Loading<String>().dataOrNull)
        assertEquals("previous", ViewState.Loading("previous").dataOrNull)
        assertEquals("loaded", ViewState.Done("loaded").dataOrNull)
        assertNull(ViewState.Failed<String>(error = error).dataOrNull)
        assertEquals("previous", ViewState.Failed("previous", error).dataOrNull)
    }

    @Test
    fun isLoadingOnlyForLoading() {
        assertTrue(ViewState.Loading<String>().isLoading)
        assertFalse(ViewState.Done("x").isLoading)
        assertFalse(ViewState.Failed<String>(error = error).isLoading)
    }

    @Test
    fun failedKeepsTheExceptionNotRenderedText() {
        assertSame(error, ViewState.Failed<String>(error = error).error)
    }

    // Result

    @Test
    fun successCarriesDataAndAnOptionalMessage() {
        assertEquals("", Result.Success(1).message)
        assertEquals("Order placed.", Result.Success(1, "Order placed.").message)
    }

    @Test
    fun accessorsReadEachVariant() {
        val success: Result<Int> = Result.Success(1)
        val failure: Result<Int> = Result.Failure(error)
        assertTrue(success.isSuccess)
        assertFalse(failure.isSuccess)
        assertEquals(1, success.dataOrNull)
        assertNull(failure.dataOrNull)
        assertNull(success.errorOrNull)
        assertSame(error, failure.errorOrNull)
    }

    @Test
    fun foldRunsTheMatchingBranchWithATypedError() {
        assertEquals("1", Result.Success(1).fold({ it.toString() }, { it.code }))
        assertEquals("ORD-R-GO-TST", (Result.Failure(error) as Result<Int>).fold({ it.toString() }, { it.code }))
    }

    @Test
    fun onSuccessAndOnFailureRunOnlyForTheirVariant() {
        val seen = mutableListOf<String>()
        Result.Success(1).onSuccess { seen += "success $it" }.onFailure { seen += "failure" }
        (Result.Failure(error) as Result<Int>).onSuccess { seen += "success" }.onFailure { seen += "failure ${it.typeCode}" }
        assertEquals(listOf("success 1", "failure TST"), seen)
    }

    @Test
    fun mapKeepsTheMessageAndPassesFailuresThrough() {
        assertEquals(Result.Success("2", "Saved."), Result.Success(2, "Saved.").map { it.toString() })
        val failure: Result<Int> = Result.Failure(error)
        assertSame(failure, failure.map { it + 1 })
    }

    @Test
    fun getOrThrowReturnsTheDataOrThrowsTheSinewException() {
        assertEquals(1, Result.Success(1).getOrThrow())
        val thrown = assertFailsWith<TestException> { (Result.Failure(error) as Result<Int>).getOrThrow() }
        assertSame(error, thrown)
    }
}
