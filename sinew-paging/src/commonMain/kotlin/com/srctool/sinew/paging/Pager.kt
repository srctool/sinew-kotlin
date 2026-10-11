package com.srctool.sinew.paging

import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.models.dataOrNull
import com.srctool.sinew.models.fold
import com.srctool.sinew.models.isSuccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Loads pages so that only the newest load can write state: a refresh beats a running `More`, a new query beats an
 * old one, and a superseded load's fetch is cancelled.
 *
 * The pager holds no UI state. It reads the current [PagingState] from the page's state and writes the next one
 * through `update`, the view model's `setState`. It remembers only its strategy, the newest request id, the newest
 * load's job, and the query of the `Initial` load that is running or succeeded, so `Initial` runs at most once per
 * query.
 */
public class Pager<T> internal constructor(
    private val strategy: PagingStrategy<T>,
    private val itemKey: ((T) -> Any)?,
) {
    private val mutex = Mutex()

    @Volatile
    private var newestId = 0L                         // written under the mutex; read by cancel()

    @Volatile
    private var cancelledUpTo = 0L                    // loads with an id up to this one were cancelled

    @Volatile
    private var running: Job? = null
    private var initialQuery: String? = null          // the query of the last Initial that is running or succeeded
    private var initialRunId: Long? = null            // the id of the Initial currently running, if any

    /**
     * Runs one load and suspends until it's written (or dropped). `Initial` and `Refresh` supersede whatever is
     * running; `More` only starts on a loaded list with a next page, or to retry a failed `More`.
     */
    public suspend fun load(type: LoadType, current: PagingState<T>, query: String = "", update: (PagingState<T>) -> Unit): Unit =
        coroutineScope {
            val previous = if (type == LoadType.More) current.view.dataOrNull else null
            val id = mutex.withLock {
                if (!allowed(type, current, query)) return@coroutineScope
                if (type != LoadType.More) running?.cancel()                // supersede: cancel the older load's fetch, never a caller
                if (type == LoadType.Initial) {
                    initialQuery = query
                    initialRunId = newestId + 1
                }
                (++newestId).also { update(PagingState(ViewState.Loading(previous), type)) }
            }
            val fetch = async { if (previous != null) strategy.next(previous, query) else strategy.first(query) }   // a child job the pager can cancel
            mutex.withLock { if (id == newestId) running = fetch }
            var succeeded = false
            try {
                val result = fetch.await()
                mutex.withLock {
                    if (id != newestId || id <= cancelledUpTo) return@withLock   // stale or cancelled: checked and written under one lock
                    succeeded = result.isSuccess
                    result.fold(
                        onSuccess = { page -> update(PagingState(ViewState.Done(strategy.normalize(merge(previous, page))), type)) },
                        onFailure = { error -> update(PagingState(ViewState.Failed(previous, error), type)) },
                    )
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()                    // the caller was cancelled: propagate
                // otherwise this load's fetch was superseded or cancelled: nothing to write
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (running === fetch) running = null
                        if (initialRunId == id) {                           // this was the Initial that set the flag
                            initialRunId = null
                            if (!succeeded) initialQuery = null             // failed, cancelled or stale: the same query may run again
                        }
                    }
                }
            }
        }

    /** Drops the running load, for example when the page closes. A pending response changes nothing afterwards. */
    public fun cancel() {
        // Cancelling the job drops a fetch still running. A response already fetched and waiting for the lock (only
        // possible on a multi-threaded dispatcher) is dropped by this mark, which the write checks.
        cancelledUpTo = newestId
        running?.cancel()
    }

    private fun allowed(type: LoadType, current: PagingState<T>, query: String): Boolean = when (type) {
        LoadType.More -> current.canLoadMore || (current.view is ViewState.Failed && current.loadType == LoadType.More)
        LoadType.Initial -> query != initialQuery                           // ignored while it runs or after it succeeded
        LoadType.Refresh -> true
    }

    private fun merge(previous: PagingDomain<T>?, page: PagingDomain<T>): PagingDomain<T> {
        if (previous == null) return page
        val items = (previous.items + page.items).let { all -> itemKey?.let { key -> all.distinctBy(key) } ?: all }
        return PagingDomain(items, page.meta)
    }

    public companion object {
        /** Page numbers, where the backend says whether a next page exists. 0-based APIs pass `firstPage = 0`. */
        public fun <T> offset(
            firstPage: Int = 1,
            itemKey: ((T) -> Any)? = null,
            fetch: suspend (page: Int, query: String) -> Result<PagingDomain<T>>,
        ): Pager<T> = Pager(OffsetStrategy(firstPage, byTotal = false, fetch), itemKey)

        /** Page numbers, where the backend returns only a total page count. */
        public fun <T> offsetByTotal(
            firstPage: Int = 1,
            itemKey: ((T) -> Any)? = null,
            fetch: suspend (page: Int, query: String) -> Result<PagingDomain<T>>,
        ): Pager<T> = Pager(OffsetStrategy(firstPage, byTotal = true, fetch), itemKey)

        /** An opaque cursor in `meta.nextKey`. */
        public fun <T> cursor(
            itemKey: ((T) -> Any)? = null,
            fetch: suspend (cursor: String, query: String) -> Result<PagingDomain<T>>,
        ): Pager<T> = Pager(CursorStrategy(fetch), itemKey)

        /** "Items after this ID", with [itemId] reading an item's ID. */
        public fun <T> lastItem(
            itemId: (T) -> String,
            itemKey: ((T) -> Any)? = null,
            fetch: suspend (lastId: String?, query: String) -> Result<PagingDomain<T>>,
        ): Pager<T> = Pager(LastItemStrategy(itemId, fetch), itemKey)
    }
}
