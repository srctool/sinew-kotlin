@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.presentation

import app.cash.turbine.test
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaDomain
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.paging.LoadType
import com.srctool.sinew.paging.Pager
import com.srctool.sinew.paging.PagingState
import com.srctool.sinew.testing.ControlledFetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The sample order list from the Presentation note: events in, states and effects out, no framework.

private data class OrderListState(val orders: PagingState<String> = PagingState(), val cancelling: Boolean = false)

private sealed interface OrderListEvent {
    data object Opened : OrderListEvent
    data object Refreshed : OrderListEvent
    data object EndReached : OrderListEvent
    data object RetryTapped : OrderListEvent
    data class CancelTapped(val id: String) : OrderListEvent
}

private sealed interface OrderListAction {
    data class Load(val type: LoadType) : OrderListAction
    data class Cancel(val id: String) : OrderListAction
}

private sealed interface OrderListEffect {
    data class Cancelled(val message: String) : OrderListEffect
    data class ShowError(val error: SinewException) : OrderListEffect
}

private class OrderListHandler(
    fetch: ControlledFetch<String>,
    private val cancelOrder: suspend (String) -> Result<Unit>,
    scope: CoroutineScope,
) : EventActionHandler<OrderListState, OrderListEvent, OrderListAction, OrderListEffect>(OrderListState(), scope) {
    private val pager = Pager.offset<String> { page, query -> fetch(page, query) }

    override fun mapEventToAction(event: OrderListEvent): OrderListAction? = when (event) {
        OrderListEvent.Opened -> OrderListAction.Load(LoadType.Initial)            // a repeat is ignored by the pager
        OrderListEvent.Refreshed -> OrderListAction.Load(LoadType.Refresh)
        OrderListEvent.EndReached -> if (currentState.orders.canLoadMore) OrderListAction.Load(LoadType.More) else null
        OrderListEvent.RetryTapped -> OrderListAction.Load(currentState.orders.retryType)
        is OrderListEvent.CancelTapped -> if (currentState.cancelling) null else OrderListAction.Cancel(event.id)
    }

    override suspend fun onAction(action: OrderListAction) {
        when (action) {
            is OrderListAction.Load -> pager.load(action.type, currentState.orders) { orders -> setState { copy(orders = orders) } }
            is OrderListAction.Cancel -> {
                setState { copy(cancelling = true) }
                val result = cancelOrder(action.id)
                setState { copy(cancelling = false) }
                when (result) {
                    is Result.Success -> emitEffect(OrderListEffect.Cancelled(result.message))
                    is Result.Failure -> emitEffect(OrderListEffect.ShowError(result.error))
                }
            }
        }
    }
}

// The once-on-open pattern for plain ViewState slices, loaded in parallel.

private data class HomeState(val banner: ViewState<String> = ViewState.Loading(), val promos: ViewState<String> = ViewState.Loading())

private class HomeHandler(
    private val banner: CompletableDeferred<String>,
    private val promos: CompletableDeferred<String>,
    scope: CoroutineScope,
) : EventActionHandler<HomeState, String, String, Nothing>(HomeState(), scope) {
    private var opened = false
    var loadsStarted = 0

    override fun mapEventToAction(event: String): String? = if (opened) null else { opened = true; "loadAll" }

    override suspend fun onAction(action: String) = coroutineScope {
        loadsStarted++
        launch { val text = banner.await(); setState { copy(banner = ViewState.Done(text)) } }
        launch { val text = promos.await(); setState { copy(promos = ViewState.Done(text)) } }
        Unit
    }
}

class SampleHandlersTest {
    private val offline = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "GO")
    private fun page(items: List<String>, page: Int, hasNext: Boolean) = PagingDomain(items, PagingMetaDomain(page = page, hasNextPage = hasNext))

    @Test
    fun theOrderListRunsEndToEndWithNoFramework() = runTest {
        val fetch = ControlledFetch<String>()
        val cancel = CompletableDeferred<Result<Unit>>()
        val handler = OrderListHandler(fetch, { cancel.await() }, handlerScope())

        handler.onEvent(OrderListEvent.Opened); runCurrent()
        assertEquals(PagingState(ViewState.Loading(), LoadType.Initial), handler.state.value.orders)
        handler.onEvent(OrderListEvent.Opened); runCurrent()                       // a rotation re-sends Opened
        assertEquals(1, fetch.calls.size)

        fetch.complete(0, Result.Success(page(listOf("a", "b"), 1, true))); runCurrent()
        assertTrue(handler.state.value.orders.canLoadMore)

        handler.onEvent(OrderListEvent.EndReached); runCurrent()
        fetch.complete(1, Result.Failure(offline)); runCurrent()
        assertIs<ViewState.Failed<*>>(handler.state.value.orders.view)

        handler.onEvent(OrderListEvent.RetryTapped); runCurrent()
        assertEquals(2, fetch.calls[2].key)                                        // retry repeats the failed page
        fetch.complete(2, Result.Success(page(listOf("c"), 2, false))); runCurrent()
        assertEquals(listOf("a", "b", "c"), (handler.state.value.orders.view as ViewState.Done).data.items)

        handler.onEvent(OrderListEvent.EndReached); runCurrent()                   // last page: no action
        assertEquals(3, fetch.calls.size)

        handler.effects.test {
            handler.onEvent(OrderListEvent.CancelTapped("a")); runCurrent()
            assertTrue(handler.state.value.cancelling)
            handler.onEvent(OrderListEvent.CancelTapped("a")); runCurrent()       // guarded by state
            cancel.complete(Result.Success(Unit, message = "Order cancelled.")); runCurrent()
            assertEquals(OrderListEffect.Cancelled("Order cancelled."), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, handler.state.value.cancelling)
        fetch.assertAllCompleted()
    }

    @Test
    fun aFailedActionIsAnEffectNotState() = runTest {
        val handler = OrderListHandler(ControlledFetch(), { Result.Failure(offline) }, handlerScope())
        handler.effects.test {
            handler.onEvent(OrderListEvent.CancelTapped("a")); runCurrent()
            assertEquals(OrderListEffect.ShowError(offline), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(OrderListState(), handler.state.value)                       // the page is still valid
    }

    @Test
    fun openedTwiceStartsTheParallelLoadsOnce() = runTest {
        val banner = CompletableDeferred<String>()
        val promos = CompletableDeferred<String>()
        val handler = HomeHandler(banner, promos, handlerScope())
        handler.onEvent("Opened"); runCurrent()
        handler.onEvent("Opened"); runCurrent()
        assertEquals(1, handler.loadsStarted)

        promos.complete("50% off"); runCurrent()                                   // each slice lands on its own
        assertEquals(HomeState(banner = ViewState.Loading(), promos = ViewState.Done("50% off")), handler.state.value)
        banner.complete("Welcome"); runCurrent()
        assertEquals(HomeState(banner = ViewState.Done("Welcome"), promos = ViewState.Done("50% off")), handler.state.value)
    }
}
