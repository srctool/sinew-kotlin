@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.paging

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaDomain
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.testing.ControlledFetch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A page's paging slice and its pager, the way a view model holds them. */
private class Page(val fetch: ControlledFetch<String> = ControlledFetch(), itemKey: ((String) -> Any)? = null) {
    val pager = Pager.offset(itemKey = itemKey) { page, query -> fetch(page, query) }
    var state = PagingState<String>()
    val history = mutableListOf<PagingState<String>>()

    fun TestScope.load(type: LoadType, query: String = ""): Job = launch {
        pager.load(type, state, query) { state = it; history += it }
    }
}

private fun meta(page: Int, hasNext: Boolean) = PagingMetaDomain(page = page, hasNextPage = hasNext)
private fun done(items: List<String>, page: Int, hasNext: Boolean) = ViewState.Done(PagingDomain(items, meta(page, hasNext)))
private val offline = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "GO")

class PagerTest {
    // The load table: loading, success and failure for each load type.

    @Test
    fun initialShowsAFullSkeletonThenPageOne() = runTest {
        with(Page()) {
            load(LoadType.Initial); runCurrent()
            assertEquals(PagingState(ViewState.Loading(), LoadType.Initial), state)
            fetch.succeed(0, listOf("a", "b"), meta(1, true)); runCurrent()
            assertEquals(PagingState(done(listOf("a", "b"), 1, true), LoadType.Initial), state)
            fetch.assertAllCompleted()
        }
    }

    @Test
    fun initialFailureIsAFirstPageError() = runTest {
        with(Page()) {
            load(LoadType.Initial); runCurrent()
            fetch.fail(0, offline); runCurrent()
            assertEquals(PagingState<String>(ViewState.Failed(error = offline), LoadType.Initial), state)
        }
    }

    @Test
    fun refreshShowsAFullSkeletonThenPageOne() = runTest {
        with(Page()) {
            state = PagingState(done(listOf("a"), 1, true), LoadType.Initial)
            load(LoadType.Refresh); runCurrent()
            assertEquals(PagingState(ViewState.Loading(), LoadType.Refresh), state)
            fetch.succeed(0, listOf("z"), meta(1, false)); runCurrent()
            assertEquals(PagingState(done(listOf("z"), 1, false), LoadType.Refresh), state)
            load(LoadType.Refresh); runCurrent()
            fetch.fail(1, offline); runCurrent()
            assertEquals(PagingState<String>(ViewState.Failed(error = offline), LoadType.Refresh), state)
        }
    }

    @Test
    fun moreKeepsTheItemsWhileLoadingThenMerges() = runTest {
        with(Page()) {
            val first = PagingDomain(listOf("a", "b"), meta(1, true))
            state = PagingState(ViewState.Done(first), LoadType.Initial)
            load(LoadType.More); runCurrent()
            assertEquals(PagingState(ViewState.Loading(first), LoadType.More), state)
            assertEquals(2, fetch.calls.single().key)
            fetch.succeed(0, listOf("c"), meta(2, false)); runCurrent()
            assertEquals(PagingState(done(listOf("a", "b", "c"), 2, false), LoadType.More), state)
        }
    }

    @Test
    fun moreFailureKeepsTheItemsWithARetryRow() = runTest {
        with(Page()) {
            val first = PagingDomain(listOf("a"), meta(1, true))
            state = PagingState(ViewState.Done(first), LoadType.Initial)
            load(LoadType.More); runCurrent()
            fetch.fail(0, offline); runCurrent()
            assertEquals(PagingState(ViewState.Failed(first, offline), LoadType.More), state)
            assertEquals(LoadType.More, state.retryType)
        }
    }

    // Guards

    @Test
    fun moreWithNoNextPageSendsNoRequestAndChangesNothing() = runTest {
        with(Page()) {
            state = PagingState(done(listOf("a"), 1, false), LoadType.Initial)
            load(LoadType.More); runCurrent()
            assertTrue(fetch.calls.isEmpty())
            assertTrue(history.isEmpty())
        }
    }

    @Test
    fun moreWhileALoadRunsIsIgnored() = runTest {
        with(Page()) {
            state = PagingState(done(listOf("a"), 1, true), LoadType.Initial)
            load(LoadType.More); runCurrent()
            load(LoadType.More); runCurrent()
            assertEquals(1, fetch.calls.size)
            fetch.succeed(0, listOf("b"), meta(2, false)); runCurrent()
            fetch.assertAllCompleted()
        }
    }

    @Test
    fun theFirstLoadWithTheDefaultMetaStillFetches() = runTest {
        with(Page()) {
            load(LoadType.Initial); runCurrent()
            assertEquals(1, fetch.calls.size)
            fetch.succeed(0, emptyList(), meta(1, false)); runCurrent()
        }
    }

    @Test
    fun anEmptyFirstPageIsDoneEmpty() = runTest {
        with(Page()) {
            load(LoadType.Initial); runCurrent()
            fetch.succeed(0, emptyList(), meta(1, false)); runCurrent()
            assertEquals(PagingState(done(emptyList(), 1, false), LoadType.Initial), state)
        }
    }

    // Initial runs at most once per query

    @Test
    fun twoInitialsWithTheSameQueryStartOneFetch() = runTest {
        with(Page()) {
            load(LoadType.Initial, "shoe"); runCurrent()
            load(LoadType.Initial, "shoe"); runCurrent()
            assertEquals(1, fetch.calls.size)
            fetch.succeed(0, listOf("a"), meta(1, false)); runCurrent()
            load(LoadType.Initial, "shoe"); runCurrent()                       // a re-sent Opened after success
            assertEquals(1, fetch.calls.size)
        }
    }

    @Test
    fun aFailedInitialRunsAgain() = runTest {
        with(Page()) {
            load(LoadType.Initial); runCurrent()
            fetch.fail(0, offline); runCurrent()
            load(LoadType.Initial); runCurrent()
            assertEquals(2, fetch.calls.size)
            fetch.succeed(1, listOf("a"), meta(1, false)); runCurrent()
            assertEquals(PagingState(done(listOf("a"), 1, false), LoadType.Initial), state)
        }
    }

    @Test
    fun anInitialSupersededByARefreshLetsTheSameQueryRunAgain() = runTest {
        with(Page()) {
            load(LoadType.Initial, "q"); runCurrent()
            load(LoadType.Refresh, "q"); runCurrent()
            assertTrue(fetch.calls[0].cancelled)
            fetch.succeed(1, listOf("r"), meta(1, false)); runCurrent()
            load(LoadType.Initial, "q"); runCurrent()
            assertEquals(3, fetch.calls.size)
            fetch.succeed(2, listOf("a"), meta(1, false)); runCurrent()
            fetch.assertAllCompleted()
        }
    }

    @Test
    fun aNewQueryRuns() = runTest {
        with(Page()) {
            load(LoadType.Initial, "shoe"); runCurrent()
            fetch.succeed(0, listOf("s"), meta(1, false)); runCurrent()
            load(LoadType.Initial, "hat"); runCurrent()
            assertEquals("hat", fetch.calls[1].query)
            fetch.succeed(1, listOf("h"), meta(1, false)); runCurrent()
            assertEquals(PagingState(done(listOf("h"), 1, false), LoadType.Initial), state)
        }
    }

    // Races

    @Test
    fun refreshDuringMoreCancelsTheMoreFetchAndDropsItsResult() = runTest {
        with(Page()) {
            state = PagingState(done(listOf("a"), 1, true), LoadType.Initial)
            load(LoadType.More); runCurrent()
            load(LoadType.Refresh); runCurrent()
            assertTrue(fetch.calls[0].cancelled, "the More fetch coroutine was not cancelled")
            fetch.succeed(1, listOf("z"), meta(1, false)); runCurrent()
            assertEquals(PagingState(done(listOf("z"), 1, false), LoadType.Refresh), state)
            fetch.assertAllCompleted()
        }
    }

    @Test
    fun searchDuringMore() = runTest {
        with(Page()) {
            state = PagingState(done(listOf("a"), 1, true), LoadType.Initial)
            load(LoadType.More, "shoe"); runCurrent()
            load(LoadType.Initial, "hat"); runCurrent()
            assertTrue(fetch.calls[0].cancelled)
            assertEquals(1, fetch.calls[1].key)
            assertEquals("hat", fetch.calls[1].query)
            fetch.succeed(1, listOf("h"), meta(1, false)); runCurrent()
            assertEquals(PagingState(done(listOf("h"), 1, false), LoadType.Initial), state)
        }
    }

    @Test
    fun aStaleResponseNeverReachesTheState() = runTest {
        with(Page()) {
            load(LoadType.Initial, "shoe"); runCurrent()
            load(LoadType.Initial, "hat"); runCurrent()
            fetch.succeed(1, listOf("h"), meta(1, false)); runCurrent()
            fetch.succeed(0, listOf("s"), meta(1, false)); runCurrent()          // the old query's response, late
            assertEquals(PagingState(done(listOf("h"), 1, false), LoadType.Initial), state)
            assertFalse(history.any { it.view.let { v -> v is ViewState.Done && v.data.items == listOf("s") } })
        }
    }

    @Test
    fun aFetchThatIgnoresCancellationIsStillDropped() = runTest {
        val fetch = ControlledFetch<String>()
        val pager = Pager.offset<String> { page, query -> withContext(NonCancellable) { fetch(page, query) } }   // a blocking call
        var state = PagingState<String>()
        launch { pager.load(LoadType.Initial, state, "shoe") { state = it } }; runCurrent()
        launch { pager.load(LoadType.Initial, state, "hat") { state = it } }; runCurrent()
        fetch.succeed(1, listOf("h"), meta(1, false)); runCurrent()
        fetch.succeed(0, listOf("s"), meta(1, false)); runCurrent()              // the old query's response still arrives
        assertEquals(PagingState(done(listOf("h"), 1, false), LoadType.Initial), state)
    }

    @Test
    fun aResponseThatArrivesAfterANewerLoadStartedIsDropped() = runTest {
        with(Page()) {
            load(LoadType.Initial, "shoe"); runCurrent()
            fetch.succeed(0, listOf("s"), meta(1, false))                       // fetched, but its write runs after…
            load(LoadType.Initial, "hat"); runCurrent()                         // …the newer load has started
            assertFalse(fetch.calls[0].cancelled, "the old fetch had already returned; only the id check can drop it")
            assertFalse(history.any { (it.view as? ViewState.Done)?.data?.items == listOf("s") }, "the stale page was written")
            fetch.succeed(1, listOf("h"), meta(1, false)); runCurrent()
            assertEquals(PagingState(done(listOf("h"), 1, false), LoadType.Initial), state)
        }
    }

    @Test
    fun retryRepeatsFailedPage() = runTest {
        with(Page()) {
            state = PagingState(done(listOf("p1", "p2"), 2, true), LoadType.More)
            load(LoadType.More); runCurrent()
            assertEquals(3, fetch.calls[0].key)
            fetch.fail(0, offline); runCurrent()
            load(state.retryType); runCurrent()
            assertEquals(3, fetch.calls[1].key, "retry fetched page ${fetch.calls[1].key}, not the failed page 3")
            assertEquals(ViewState.Loading(PagingDomain(listOf("p1", "p2"), meta(2, true))), state.view)
            fetch.succeed(1, listOf("p3"), meta(3, false)); runCurrent()
            assertEquals(PagingState(done(listOf("p1", "p2", "p3"), 3, false), LoadType.More), state)
        }
    }

    @Test
    fun itemKeyDropsDuplicatesAcrossPages() = runTest {
        with(Page(itemKey = { it })) {
            state = PagingState(done(listOf("a", "b"), 1, true), LoadType.Initial)
            load(LoadType.More); runCurrent()
            fetch.succeed(0, listOf("b", "c"), meta(2, false)); runCurrent()
            assertEquals(listOf("a", "b", "c"), (state.view as ViewState.Done).data.items)
        }
    }

    // Cancellation

    @Test
    fun cancelDropsTheRunningLoad() = runTest {
        with(Page()) {
            load(LoadType.Initial); runCurrent()
            val before = history.size
            pager.cancel(); runCurrent()
            assertTrue(fetch.calls[0].cancelled)
            assertEquals(before, history.size, "the state changed after cancel()")
        }
    }

    @Test
    fun cancellingTheCallerLeavesNoRunningFetch() = runTest {
        with(Page()) {
            val caller = load(LoadType.Initial); runCurrent()
            caller.cancel(); runCurrent()
            assertTrue(caller.isCancelled)
            assertTrue(fetch.calls[0].cancelled)
            fetch.assertAllCompleted()
        }
    }
}
