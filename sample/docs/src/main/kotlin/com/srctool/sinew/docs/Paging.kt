package com.srctool.sinew.docs

import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.dataOrNull
import com.srctool.sinew.paging.LoadType
import com.srctool.sinew.paging.Pager
import com.srctool.sinew.paging.PagingState

// region pager
suspend fun loadAll(fetch: suspend (page: Int, query: String) -> Result<PagingDomain<Order>>): List<Order> {
    val pager = Pager.offset<Order>(itemKey = { it.id }, fetch = fetch)
    var state = PagingState<Order>()

    pager.load(LoadType.Initial, state) { state = it }
    while (state.canLoadMore) pager.load(LoadType.More, state) { state = it }
    return state.view.dataOrNull?.items.orEmpty()
}
// endregion
