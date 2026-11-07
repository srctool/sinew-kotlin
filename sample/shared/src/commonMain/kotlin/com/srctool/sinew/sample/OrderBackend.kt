package com.srctool.sinew.sample

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaDomain
import com.srctool.sinew.models.Result
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One row of the list. */
data class Order(val id: Int, val title: String, val status: String)

/** Where the screen's pages come from. */
fun interface OrderBackend {
    suspend fun orders(page: Int): Result<PagingDomain<Order>>
}

/**
 * A backend in memory, so the sample runs anywhere and shows every state: each list loads with a delay, its second
 * page fails the first time, and a refresh starts that over.
 */
class MockOrderBackend(
    private val latency: Duration = 800.milliseconds,
    private val pageSize: Int = 15,
    private val pageCount: Int = 3,
) : OrderBackend {
    private var nextPageFails = true

    override suspend fun orders(page: Int): Result<PagingDomain<Order>> {
        delay(latency)
        if (page == 1) nextPageFails = true
        if (page == 2 && nextPageFails) {
            nextPageFails = false
            return Result.Failure(NoInternetConnectionException("SMP", ExceptionLayer.Repository, "ORD"))
        }
        val first = (page - 1) * pageSize + 1
        val rows = (first until first + pageSize).map { Order(it, "Order #$it", if (it % 3 == 0) "Delivered" else "On the way") }
        return Result.Success(PagingDomain(rows, PagingMetaDomain(page = page, limit = pageSize, hasNextPage = page < pageCount)))
    }
}
