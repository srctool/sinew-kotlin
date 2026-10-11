package com.srctool.sinew.paging

import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.ViewState

/** Which load a paging state belongs to. A new search is an [Initial] with the new query. */
public enum class LoadType { Initial, Refresh, More }

/**
 * The paging slice of a page's state. [loadType] stays set after the load finishes, so the UI can tell a
 * first-page failure from a next-page failure, and retry knows what to repeat. It starts as a loading
 * [Initial], so a list shows its skeleton from the first frame.
 */
public data class PagingState<out T>(
    val view: ViewState<PagingDomain<T>> = ViewState.Loading(),
    val loadType: LoadType = LoadType.Initial,
) {
    /** Whether a [LoadType.More] may start: the list is loaded and the backend has a next page. */
    val canLoadMore: Boolean get() = view is ViewState.Done && view.data.meta.hasNextPage

    /** The load to repeat after a failure: the one that failed, or [LoadType.Initial]. */
    val retryType: LoadType get() = if (view is ViewState.Failed) loadType else LoadType.Initial
}
