package com.srctool.sinew.paging

import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaDomain
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagingStateTest {
    private val error = UnexpectedException("ORD", ExceptionLayer.Repository, "GO")
    private fun page(hasNext: Boolean) = PagingDomain(listOf("a"), PagingMetaDomain(page = 1, hasNextPage = hasNext))

    @Test
    fun startsAsALoadingInitial() {
        assertEquals(PagingState<String>(ViewState.Loading(), LoadType.Initial), PagingState())
    }

    @Test
    fun canLoadMoreOnlyWhenDoneWithANextPage() {
        for (type in LoadType.entries) {
            assertTrue(PagingState(ViewState.Done(page(hasNext = true)), type).canLoadMore, "$type")
            assertFalse(PagingState(ViewState.Done(page(hasNext = false)), type).canLoadMore, "$type")
            assertFalse(PagingState(ViewState.Loading(page(hasNext = true)), type).canLoadMore, "$type")
            assertFalse(PagingState(ViewState.Failed(page(hasNext = true), error), type).canLoadMore, "$type")
        }
    }

    @Test
    fun retryRepeatsTheLoadThatFailed() {
        for (type in LoadType.entries) {
            assertEquals(type, PagingState<String>(ViewState.Failed(error = error), type).retryType)
            assertEquals(LoadType.Initial, PagingState(ViewState.Done(page(true)), type).retryType)
            assertEquals(LoadType.Initial, PagingState<String>(ViewState.Loading(), type).retryType)
        }
    }

    // Strategies: the first request, the next request and the has-next rule.

    private val requests = mutableListOf<Any?>()
    private fun reply(meta: PagingMetaDomain, items: List<String> = listOf("x")): Result<PagingDomain<String>> =
        Result.Success(PagingDomain(items, meta))

    @Test
    fun offsetRequestsFirstPageThenPagePlusOneAndTrustsTheFlag() = runTest {
        val strategy = OffsetStrategy<String>(firstPage = 1, byTotal = false) { page, _ -> requests += page; reply(PagingMetaDomain(hasNextPage = true)) }
        val first = strategy.first("q").getOrThrow()
        val second = strategy.next(first, "q").getOrThrow()
        assertEquals(listOf<Any?>(1, 2), requests)
        assertTrue(strategy.normalize(second).meta.hasNextPage)
        assertFalse(strategy.normalize(second.copy(meta = second.meta.copy(hasNextPage = false))).meta.hasNextPage)
    }

    @Test
    fun offsetSupportsAZeroBasedFirstPage() = runTest {
        val strategy = OffsetStrategy<String>(firstPage = 0, byTotal = false) { page, _ -> requests += page; reply(PagingMetaDomain()) }
        strategy.next(strategy.first("").getOrThrow(), "")
        assertEquals(listOf<Any?>(0, 1), requests)
    }

    @Test
    fun theRequestedPageWinsOverTheReportedOne() = runTest {
        val strategy = OffsetStrategy<String>(firstPage = 1, byTotal = false) { page, _ -> requests += page; reply(PagingMetaDomain(page = 0)) }
        val first = strategy.first("").getOrThrow()
        assertEquals(1, first.meta.page)
        assertEquals(2, strategy.next(first, "").getOrThrow().meta.page)
    }

    @Test
    fun offsetByTotalHasANextPageWhilePageIsBelowTotalPage() = runTest {
        val strategy = OffsetStrategy<String>(firstPage = 1, byTotal = true) { _, _ -> reply(PagingMetaDomain(totalPage = 2)) }
        val first = strategy.first("").getOrThrow()
        assertTrue(strategy.normalize(first).meta.hasNextPage)
        assertFalse(strategy.normalize(strategy.next(first, "").getOrThrow()).meta.hasNextPage)
    }

    @Test
    fun cursorRequestsEmptyThenNextKeyAndStopsOnAnEmptyKey() = runTest {
        val strategy = CursorStrategy<String> { cursor, _ -> requests += cursor; reply(PagingMetaDomain(hasNextPage = true, nextKey = "c2")) }
        val first = strategy.first("").getOrThrow()
        strategy.next(first, "")
        assertEquals(listOf<Any?>("", "c2"), requests)
        assertTrue(strategy.normalize(first).meta.hasNextPage)
        assertFalse(strategy.normalize(first.copy(meta = first.meta.copy(nextKey = ""))).meta.hasNextPage)
    }

    @Test
    fun lastItemRequestsNullThenTheLastIdAndStopsOnAnEmptyList() = runTest {
        val strategy = LastItemStrategy<String>({ "id-$it" }) { lastId, _ -> requests += lastId; reply(PagingMetaDomain(hasNextPage = true), listOf("a", "b")) }
        val first = strategy.first("").getOrThrow()
        strategy.next(first, "")
        assertEquals(listOf<Any?>(null, "id-b"), requests)
        assertTrue(strategy.normalize(first).meta.hasNextPage)
        assertFalse(strategy.normalize(first.copy(items = emptyList())).meta.hasNextPage)
    }
}
