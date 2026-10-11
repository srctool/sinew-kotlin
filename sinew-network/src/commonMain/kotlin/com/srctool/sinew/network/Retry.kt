package com.srctool.sinew.network

import com.srctool.sinew.exception.PollingTimeOutException
import com.srctool.sinew.exception.RateLimitedException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block] again after a retryable failure, with exponential backoff and full jitter: before attempt `n + 1` it
 * waits a random time up to `initialDelay × 2ⁿ`, capped at [maxDelay]. A 429 waits at least its `retryAfter`; one
 * asking for longer than [maxDelay] is returned at once, so the page shows the wait instead of a spinner.
 *
 * When it gives up, it returns the **last failure unchanged**, so the user sees the real reason. A failure
 * [retryIf] rejects (by default, one that isn't `retryable`) is returned at once.
 *
 * For idempotent requests only: reads, and writes carrying an `Idempotency-Key`. Retry in one layer only, never
 * around a call that's already retried. The client itself never retries.
 *
 * ```kotlin
 * retryWithBackoff { processApiCall("ORD", "LIST") { api.list(page) } }
 * ```
 */
public suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 3,
    initialDelay: Duration = 500.milliseconds,
    maxDelay: Duration = 5.seconds,
    retryIf: (SinewException) -> Boolean = { it.retryable },
    block: suspend () -> Result<T>,
): Result<T> = retryWithBackoff(maxAttempts, initialDelay, maxDelay, retryIf, Random.Default, block)

internal suspend fun <T> retryWithBackoff(
    maxAttempts: Int,
    initialDelay: Duration,
    maxDelay: Duration,
    retryIf: (SinewException) -> Boolean,
    random: Random,
    block: suspend () -> Result<T>,
): Result<T> {
    require(maxAttempts >= 1) { "retryWithBackoff: maxAttempts must be at least 1" }
    require(initialDelay.isPositive() && maxDelay >= initialDelay) { "retryWithBackoff: 0 < initialDelay <= maxDelay" }
    var attempt = 0
    while (true) {
        val result = block()
        if (result !is Result.Failure) return result
        attempt++
        if (attempt >= maxAttempts || !retryIf(result.error)) return result
        val backoff = minOf(maxDelay, initialDelay * (1L shl minOf(attempt - 1, 30)).toDouble())
        val jittered = random.nextLong(0, backoff.inWholeMilliseconds + 1).milliseconds
        val retryAfter = (result.error as? RateLimitedException)?.retryAfter
        if (retryAfter != null && retryAfter > maxDelay) return result
        delay(maxOf(jittered, retryAfter ?: Duration.ZERO))
    }
}

/**
 * Calls [block] until [until] accepts its data, for a backend process that finishes later (a payment, an export).
 * The wait between calls starts at [interval] and grows by 1.5× up to [maxInterval], with jitter so many clients
 * don't call in step.
 *
 * A retryable failure keeps polling; a non-retryable one is returned at once. Past [timeout], the result is a
 * `PollingTimeOutException` coded with [module] and [function], with the last failure, if any, as its cause.
 *
 * ```kotlin
 * poll("PAY", "STATUS", until = { it.status != PaymentStatus.Pending }) { processApiCall("PAY", "STATUS") { api.status(id) } }
 * ```
 */
public suspend fun <T> poll(
    module: String,
    function: String,
    interval: Duration = 2.seconds,
    maxInterval: Duration = 10.seconds,
    timeout: Duration = 60.seconds,
    until: (T) -> Boolean,
    block: suspend () -> Result<T>,
): Result<T> = poll(module, function, interval, maxInterval, timeout, until, Random.Default, block)

internal suspend fun <T> poll(
    module: String,
    function: String,
    interval: Duration,
    maxInterval: Duration,
    timeout: Duration,
    until: (T) -> Boolean,
    random: Random,
    block: suspend () -> Result<T>,
): Result<T> {
    require(interval.isPositive() && maxInterval >= interval) { "poll: 0 < interval <= maxInterval" }
    require(timeout.isPositive()) { "poll: timeout must be positive" }
    var lastFailure: SinewException? = null
    val finished = withTimeoutOrNull(timeout) {
        pollUntilDone(interval, maxInterval, until, random, block) { lastFailure = it }
    }
    return finished ?: Result.Failure(PollingTimeOutException(module, ExceptionLayer.Repository, function, cause = lastFailure))
}

/** Polls until [until] accepts the data or a failure isn't retryable; [onRetryable] sees each retryable failure. */
private suspend fun <T> pollUntilDone(
    interval: Duration,
    maxInterval: Duration,
    until: (T) -> Boolean,
    random: Random,
    block: suspend () -> Result<T>,
    onRetryable: (SinewException) -> Unit,
): Result<T> {
    var wait = interval
    while (true) {
        when (val result = block()) {
            is Result.Success -> if (until(result.data)) return result
            is Result.Failure -> if (result.error.retryable) onRetryable(result.error) else return result
        }
        // Equal jitter: half the wait fixed, half random, so the interval still grows.
        val half = wait.inWholeMilliseconds / 2
        delay((half + random.nextLong(0, half + 1)).milliseconds)
        wait = minOf(maxInterval, wait * 1.5)
    }
}
