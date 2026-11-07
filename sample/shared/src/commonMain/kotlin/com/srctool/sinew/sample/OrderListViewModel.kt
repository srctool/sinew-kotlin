package com.srctool.sinew.sample

import com.srctool.sinew.paging.LoadType
import com.srctool.sinew.paging.Pager
import com.srctool.sinew.paging.PagingState
import com.srctool.sinew.viewmodel.EventActionViewModel

data class OrderListState(val orders: PagingState<Order> = PagingState())

sealed interface OrderListEvent {
    data object Opened : OrderListEvent
    data object EndReached : OrderListEvent
    data object RetryTapped : OrderListEvent
    data object Refreshed : OrderListEvent
}

data class LoadOrders(val type: LoadType)

class OrderListViewModel(backend: OrderBackend) :
    EventActionViewModel<OrderListState, OrderListEvent, LoadOrders, Nothing>(OrderListState()) {
    private val pager = Pager.offset<Order>(itemKey = { it.id }) { page, _ -> backend.orders(page) }

    override fun mapEventToAction(event: OrderListEvent): LoadOrders? = when (event) {
        OrderListEvent.Opened -> LoadOrders(LoadType.Initial)                // the pager runs Initial once per query
        OrderListEvent.EndReached -> if (currentState.orders.canLoadMore) LoadOrders(LoadType.More) else null
        OrderListEvent.RetryTapped -> LoadOrders(currentState.orders.retryType)
        OrderListEvent.Refreshed -> LoadOrders(LoadType.Refresh)
    }

    override suspend fun onAction(action: LoadOrders) {
        pager.load(action.type, currentState.orders) { next -> setState { copy(orders = next) } }
    }
}
