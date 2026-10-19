package com.srctool.sinew.storage

import com.srctool.sinew.models.PagingEntity
import com.srctool.sinew.models.PagingMetaEntity

/**
 * One page of a local query, for `Pager.offset`: [fetch] is the app's `LIMIT :count OFFSET :offset` query. It asks
 * for one row more than [limit], which tells whether a next page exists without a `COUNT(*)`; `total` and
 * `totalPage` stay 0. A page past the last one is empty, with no next page.
 *
 * ```kotlin
 * suspend fun page(page: Int, limit: Int) = pagedQuery(page, limit) { offset, count -> dao.page(offset, count) }
 * ```
 */
public suspend fun <T> pagedQuery(
    page: Int,
    limit: Int,
    firstPage: Int = 1,
    fetch: suspend (offset: Int, count: Int) -> List<T>,
): PagingEntity<T> {
    require(limit > 0) { "limit must be positive: $limit" }
    require(page >= firstPage) { "page $page is before the first page, $firstPage" }
    val offset = (page - firstPage).toLong() * limit
    require(offset <= Int.MAX_VALUE) { "page $page is beyond what an Int offset can reach" }
    val rows = fetch(offset.toInt(), limit + 1)
    return PagingEntity(
        items = rows.take(limit),
        meta = PagingMetaEntity(page = page, limit = limit, hasNextPage = rows.size > limit, hasPreviousPage = page > firstPage),
    )
}
