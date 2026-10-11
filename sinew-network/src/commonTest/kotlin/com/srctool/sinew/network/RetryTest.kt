@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.network

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.PollingTimeOutException
import com.srctool.sinew.exception.RateLimitedException
import com.srctool.sinew.exception.ServerException
import com.srctool.sinew.exception.ServiceUnavailableException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Always the longest wait the jitter allows. */
private object Longest : Random() {
    override fun nextBits(bitCount: Int): Int = error("unused")
    override fun nextLong(from: Long, until: Long): Long = until - 1
}

/** Always no jitter at all. */
private object Shortest : Random() {
    override fun nextBits(bitCount: Int): Int = error("unused")
    override fun nextLong(from: Long, until: Long): Long = from
}

private val l = ExceptionLayer.Repository
private fun offline() = NoInternetConnectionException("ORD", l, "LIST")
private fun busy() = ServiceUnavailableException("ORD", l, "LIST", 503)

/** Answers each call from [answers] in turn, the last one repeated, and counts the calls. */
private class Answers<T>(vararg answers: Result<T>) {
    private val queue = answers.toList()
    var calls = 0
    suspend fun next(): Result<T> = queue[minOf(calls++, queue.size - 1)]
}

class RetryWithBackoffTest {
    private suspend fun <T> TestScope.retry(
        answers: Answers<T>,
        maxAttempts: Int = 3,
        random: Random = Longest,
        retryIf: (SinewException) -> Boolean = { it.retryable },
    ) = retryWithBackoff(maxAttempts, 500.milliseconds, 5.seconds, retryIf, random) { answers.next() }

    @Test
    fun aSuccessIsReturnedAtOnce() = runTest {
        val answers = Answers(Result.Success(1))
        assertEquals(Result.Success(1), retry(answers))
        assertEquals(1, answers.calls)
        assertEquals(0, currentTime)
    }

    @Test
    fun retryableFailuresAreRetriedWithGrowingWaits() = runTest {
        val answers = Answers(Result.Failure(offline()), Result.Failure(busy()), Result.Success(1))
        assertEquals(Result.Success(1), retry(answers))
        assertEquals(3, answers.calls)
        assertEquals(500 + 1000, currentTime)                                       // 500 ms, then 1 s
    }

    @Test
    fun givingUpReturnsTheLastFailureUnchanged() = runTest {
        val last = busy()
        val answers = Answers(Result.Failure(offline()), Result.Failure(offline()), Result.Failure(last))
        assertSame(last, (retry(answers) as Result.Failure).error)
        assertEquals(3, answers.calls)
    }

    @Test
    fun aNonRetryableFailureStopsAtOnce() = runTest {
        val bug = ServerException("ORD", l, "LIST", 500)
        val answers = Answers<Int>(Result.Failure(bug))
        assertSame(bug, (retry(answers) as Result.Failure).error)
        assertEquals(1, answers.calls)
        assertEquals(0, currentTime)
    }

    @Test
    fun retryIfDecides() = runTest {
        val answers = Answers<Int>(Result.Failure(ServerException("ORD", l, "LIST", 500)), Result.Success(1))
        assertEquals(Result.Success(1), retry(answers, retryIf = { it is ServerException }))
    }

    @Test
    fun theWaitIsCappedAtMaxDelay() = runTest {
        val answers = Answers<Int>(Result.Failure(offline()))
        retry(answers, maxAttempts = 6)
        assertEquals(500 + 1000 + 2000 + 4000 + 5000L, currentTime)
    }

    @Test
    fun fullJitterCanWaitNothing() = runTest {
        retry(Answers<Int>(Result.Failure(offline())), random = Shortest)
        assertEquals(0, currentTime)
    }

    @Test
    fun a429WaitsAtLeastItsRetryAfter() = runTest {
        val answers = Answers(Result.Failure(RateLimitedException("ORD", l, "LIST", 2.seconds)), Result.Success(1))
        assertEquals(Result.Success(1), retry(answers, random = Shortest))
        assertEquals(2_000, currentTime)
    }

    @Test
    fun a429AskingForLongerThanMaxDelayIsReturnedAtOnce() = runTest {
        val tooLong = RateLimitedException("ORD", l, "LIST", 120.seconds)
        val answers = Answers<Int>(Result.Failure(tooLong))
        assertSame(tooLong, (retry(answers) as Result.Failure).error)
        assertEquals(1, answers.calls)
        assertEquals(0, currentTime)
    }

    @Test
    fun oneAttemptNeverWaits() = runTest {
        retry(Answers<Int>(Result.Failure(offline())), maxAttempts = 1)
        assertEquals(0, currentTime)
    }

    @Test
    fun theSettingsAreChecked() = runTest {
        assertFailsWith<IllegalArgumentException> { retryWithBackoff(maxAttempts = 0) { Result.Success(1) } }
        assertFailsWith<IllegalArgumentException> { retryWithBackoff(initialDelay = Duration.ZERO) { Result.Success(1) } }
        assertFailsWith<IllegalArgumentException> { retryWithBackoff(initialDelay = 10.seconds, maxDelay = 1.seconds) { Result.Success(1) } }
    }
}

class PollTest {
    private data class Payment(val done: Boolean)

    private suspend fun TestScope.poll(answers: Answers<Payment>, random: Random = Longest, timeout: Duration = 60.seconds) =
        poll("PAY", "STATUS", 2.seconds, 10.seconds, timeout, { it.done }, random) { answers.next() }

    private val pending = Result.Success(Payment(done = false))
    private val done = Result.Success(Payment(done = true))

    @Test
    fun aFinishedProcessIsReturnedAtOnce() = runTest {
        assertEquals(done, poll(Answers(done)))
        assertEquals(0, currentTime)
    }

    @Test
    fun theIntervalGrowsByHalfEachTime() = runTest {
        val answers = Answers(pending, pending, pending, done)
        assertEquals(done, poll(answers))
        assertEquals(4, answers.calls)
        assertEquals(2_000 + 3_000 + 4_500L, currentTime)
    }

    @Test
    fun jitterKeepsAtLeastHalfTheInterval() = runTest {
        poll(Answers(pending, pending, done), random = Shortest)
        assertEquals(1_000 + 1_500L, currentTime)
    }

    @Test
    fun theIntervalIsCappedAtMaxInterval() = runTest {
        poll(Answers(pending, pending, pending, pending, pending, pending, done))
        assertEquals(2_000 + 3_000 + 4_500 + 6_750 + 10_000 + 10_000L, currentTime)
    }

    @Test
    fun aRetryableFailureKeepsPolling() = runTest {
        val answers = Answers(Result.Failure(offline()), pending, done)
        assertEquals(done, poll(answers))
        assertEquals(3, answers.calls)
    }

    @Test
    fun aNonRetryableFailureStopsAtOnce() = runTest {
        val bug = ServerException("PAY", l, "STATUS", 500)
        val answers = Answers<Payment>(pending, Result.Failure(bug))
        assertSame(bug, (poll(answers) as Result.Failure).error)
        assertEquals(2, answers.calls)
    }

    @Test
    fun pastTheTimeoutItsAPollingTimeOut() = runTest {
        val error = assertIs<PollingTimeOutException>((poll(Answers(pending)) as Result.Failure).error)
        assertEquals("PAY-R-STATUS-PTO", error.code)
        assertEquals(null, error.cause)
        assertEquals(60_000, currentTime)
    }

    @Test
    fun theTimeOutCarriesTheLastFailure() = runTest {
        val last = offline()
        val error = (poll(Answers<Payment>(Result.Failure(last))) as Result.Failure).error
        assertSame(last, assertIs<PollingTimeOutException>(error).cause)
    }

    @Test
    fun aSlowCallIsCutAtTheTimeout() = runTest {
        val result = poll("PAY", "STATUS", 2.seconds, 10.seconds, 5.seconds, { true }, Longest) {
            delay(30.seconds)
            Result.Success(1)
        }
        assertIs<PollingTimeOutException>((result as Result.Failure).error)
        assertEquals(5_000, currentTime)
    }

    @Test
    fun theSettingsAreChecked() = runTest {
        assertFailsWith<IllegalArgumentException> { poll<Int>("P", "S", interval = Duration.ZERO, until = { true }) { Result.Success(1) } }
        assertFailsWith<IllegalArgumentException> { poll<Int>("P", "S", interval = 5.seconds, maxInterval = 1.seconds, until = { true }) { Result.Success(1) } }
        assertFailsWith<IllegalArgumentException> { poll<Int>("P", "S", timeout = Duration.ZERO, until = { true }) { Result.Success(1) } }
    }
}
