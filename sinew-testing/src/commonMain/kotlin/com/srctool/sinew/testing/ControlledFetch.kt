package com.srctool.sinew.testing

import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaDomain
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

/**
 * A paging `fetch` the test completes by hand, so every race (a late response, a refresh during `More`, a search
 * during a load) can be written in order.
 *
 * Each call suspends until [complete] or [fail] answers it, by its index in call order. A call whose coroutine was
 * cancelled (a superseded load) is recorded as cancelled. [assertAllCompleted] fails the test if a call was left
 * pending.
 *
 * ```
 * val fetch = ControlledFetch<Order>()
 * val pager = Pager.offset<Order> { page, query -> fetch(page, query) }
 * ```
 */
public class ControlledFetch<T> {
    /** One call: the page, cursor or last ID it asked for, and its query. */
    public inner class Call internal constructor(public val key: Any?, public val query: String) {
        internal val answer = CompletableDeferred<Result<PagingDomain<T>>>()

        /** Whether the caller's coroutine was cancelled while it waited. */
        public var cancelled: Boolean = false
            internal set
    }

    private val _calls = mutableListOf<Call>()

    /** Every call so far, in order. */
    public val calls: List<Call> get() = _calls.toList()

    /** The fetch function: suspends until the test answers this call. */
    public suspend operator fun invoke(key: Any?, query: String): Result<PagingDomain<T>> {
        val call = Call(key, query)
        _calls += call
        try {
            return call.answer.await()
        } catch (e: CancellationException) {
            call.cancelled = true
            throw e
        }
    }

    /** Answers call number [call] with [result]. */
    public fun complete(call: Int, result: Result<PagingDomain<T>>) {
        check(call in _calls.indices) { "There's no call $call yet; ${_calls.size} call(s) were made" }
        check(_calls[call].answer.complete(result)) { "Call $call was already answered" }
    }

    /** Answers call number [call] with a page of [items]. */
    public fun succeed(call: Int, items: List<T>, meta: PagingMetaDomain = PagingMetaDomain()) {
        complete(call, Result.Success(PagingDomain(items, meta)))
    }

    /** Answers call number [call] with [error]. */
    public fun fail(call: Int, error: SinewException) {
        complete(call, Result.Failure(error))
    }

    /** Fails the test if a call is still waiting for an answer. Cancelled calls don't count. */
    public fun assertAllCompleted() {
        val pending = _calls.withIndex().filter { (_, call) -> !call.answer.isCompleted && !call.cancelled }.map { it.index }
        if (pending.isNotEmpty()) throw AssertionError("Calls $pending were never answered")
    }
}
