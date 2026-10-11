package com.srctool.sinew.paging

import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.map

/** How the first and next page are requested, and the has-next rule. Chosen through the `Pager` factories. */
internal interface PagingStrategy<T> {
    suspend fun first(query: String): Result<PagingDomain<T>>

    suspend fun next(previous: PagingDomain<T>, query: String): Result<PagingDomain<T>>

    /** Rewrites `meta.hasNextPage` with this strategy's rule, so `canLoadMore` only ever reads one field. */
    fun normalize(page: PagingDomain<T>): PagingDomain<T>
}

/**
 * Page numbers. The requested page wins: it's written into `meta.page`, so the next request is always
 * `page + 1`, whatever the backend reported. With [byTotal], has-next is `page < totalPage`.
 */
internal class OffsetStrategy<T>(
    private val firstPage: Int,
    private val byTotal: Boolean,
    private val fetch: suspend (page: Int, query: String) -> Result<PagingDomain<T>>,
) : PagingStrategy<T> {
    override suspend fun first(query: String) = request(firstPage, query)

    override suspend fun next(previous: PagingDomain<T>, query: String) = request(previous.meta.page + 1, query)

    private suspend fun request(page: Int, query: String) = fetch(page, query).map { it.copy(meta = it.meta.copy(page = page)) }

    override fun normalize(page: PagingDomain<T>) =
        if (byTotal) page.copy(meta = page.meta.copy(hasNextPage = page.meta.page < page.meta.totalPage)) else page
}

/** An opaque cursor in `meta.nextKey`. An empty cursor means no next page, whatever the flag says. */
internal class CursorStrategy<T>(
    private val fetch: suspend (cursor: String, query: String) -> Result<PagingDomain<T>>,
) : PagingStrategy<T> {
    override suspend fun first(query: String) = fetch("", query)

    override suspend fun next(previous: PagingDomain<T>, query: String) = fetch(previous.meta.nextKey, query)

    override fun normalize(page: PagingDomain<T>) =
        page.copy(meta = page.meta.copy(hasNextPage = page.meta.hasNextPage && page.meta.nextKey.isNotEmpty()))
}

/** "Items after this ID". An empty list has no next page. */
internal class LastItemStrategy<T>(
    private val itemId: (T) -> String,
    private val fetch: suspend (lastId: String?, query: String) -> Result<PagingDomain<T>>,
) : PagingStrategy<T> {
    override suspend fun first(query: String) = fetch(null, query)

    override suspend fun next(previous: PagingDomain<T>, query: String) = fetch(previous.items.lastOrNull()?.let(itemId), query)

    override fun normalize(page: PagingDomain<T>) =
        page.copy(meta = page.meta.copy(hasNextPage = page.meta.hasNextPage && page.items.isNotEmpty()))
}
