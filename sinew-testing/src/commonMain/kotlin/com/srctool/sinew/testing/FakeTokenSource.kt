package com.srctool.sinew.testing

import com.srctool.sinew.network.RefreshOutcome
import com.srctool.sinew.network.TokenSource
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A [TokenSource] a test drives by hand: it counts refreshes and clears, and [onRefresh] decides each refresh.
 *
 * By default a refresh rotates the token (`token-1` → `token-2` → …) and returns [RefreshOutcome.Refreshed]. Gate it
 * on a `CompletableDeferred` to hold a refresh open while other requests pile up behind it:
 *
 * ```kotlin
 * val gate = CompletableDeferred<Unit>()
 * val tokens = FakeTokenSource().apply { onRefresh = { gate.await(); rotate(); RefreshOutcome.Refreshed } }
 * ```
 */
@OptIn(ExperimentalAtomicApi::class)
public class FakeTokenSource(accessToken: String? = "token-1") : TokenSource {
    private val token = AtomicReference(accessToken)
    private val generation = AtomicInt(accessToken?.removePrefix("token-")?.toIntOrNull() ?: 0)
    private val refreshCount = AtomicInt(0)
    private val clearCount = AtomicInt(0)

    /** Decides each refresh. */
    public var onRefresh: suspend FakeTokenSource.() -> RefreshOutcome = {
        rotate()
        RefreshOutcome.Refreshed
    }

    /** How many times [refresh] was called. */
    public val refreshes: Int get() = refreshCount.load()

    /** How many times [clear] was called. */
    public val clears: Int get() = clearCount.load()

    /** Replaces the access token with the next one, `token-<n + 1>`, and returns it. */
    public fun rotate(): String = "token-${generation.incrementAndFetch()}".also { token.store(it) }

    override fun accessToken(): String? = token.load()

    /** Like a real source, a [RefreshOutcome.Rejected] refresh ends the session itself, counted in [clears]. */
    override suspend fun refresh(): RefreshOutcome {
        refreshCount.incrementAndFetch()
        return onRefresh().also { if (it == RefreshOutcome.Rejected) clear() }
    }

    override suspend fun clear() {
        clearCount.incrementAndFetch()
        token.store(null)
    }
}
