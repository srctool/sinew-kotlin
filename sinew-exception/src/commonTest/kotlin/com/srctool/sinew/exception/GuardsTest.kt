package com.srctool.sinew.exception

import com.srctool.sinew.models.EnvelopeDataMissing
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.models.errorOrNull
import com.srctool.sinew.models.getOrThrow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GuardsTest {
    private class RecordingReporter : CrashReporter {
        val reports = mutableListOf<SinewException>()
        override fun recordNonFatal(error: SinewException) {
            reports += error
        }
    }

    private class OutOfStockException : SinewException("ORD", ExceptionLayer.UseCase, "PO") {
        override val typeCode = "OOS"
        override val errorMessage = ErrorMessage.Local("orders.error.outOfStock", fallback = "Out of stock.")
    }

    private val reporter = RecordingReporter()
    private val timeoutHandler = ExceptionHandler({ it is IllegalStateException && it.message == "timeout" }) {
        RequestTimeOutException("ORD", ExceptionLayer.Repository, "GO", cause = it)
    }

    @BeforeTest
    fun installReporter() {
        assertSame(CrashReporter.None, CrashReporter.current, "a reporter leaked from another test")
        CrashReporter.install(reporter)
    }

    @AfterTest
    fun resetReporter() {
        CrashReporter.install(CrashReporter.None)
    }

    private suspend fun <T> guard(handlers: List<ExceptionHandler> = emptyList(), block: suspend () -> T) =
        processCall("ORD", "GO", ExceptionLayer.Repository, handlers, block)

    // processCall

    @Test
    fun successReturnsTheValue() = runTest {
        assertEquals(Result.Success(42), guard { 42 })
    }

    @Test
    fun aLowerLayerSinewExceptionPassesThroughUnchanged() = runTest {
        val lower = NotFoundException("ORD", ExceptionLayer.Repository, "GET", ErrorMessage.Server("Gone."))
        val result = guard(listOf(timeoutHandler)) { throw lower }
        assertSame(lower, result.errorOrNull)
        assertEquals("ORD-R-GET-NTF", result.errorOrNull?.code)
        assertTrue(reporter.reports.isEmpty())
    }

    @Test
    fun aMatchedErrorIsTransformedAndNotReported() = runTest {
        val result = guard(listOf(timeoutHandler)) { throw IllegalStateException("timeout") }
        val error = assertIs<RequestTimeOutException>(result.errorOrNull)
        assertEquals("ORD-R-GO-RTO", error.code)
        assertTrue(reporter.reports.isEmpty())
    }

    @Test
    fun theFirstMatchWins() = runTest {
        val general = ExceptionHandler({ true }) { UnexpectedException("ORD", ExceptionLayer.Repository, "GO", it) }
        assertIs<RequestTimeOutException>(guard(listOf(timeoutHandler, general)) { throw IllegalStateException("timeout") }.errorOrNull)
    }

    @Test
    fun anUnmatchedErrorBecomesUnexpectedAndIsReportedOnce() = runTest {
        val bug = IllegalArgumentException("bug")
        val result = guard(listOf(timeoutHandler)) { throw bug }
        val error = assertIs<UnexpectedException>(result.errorOrNull)
        assertEquals("ORD-R-GO-GEN", error.code)
        assertSame(bug, error.cause)
        assertEquals(listOf<SinewException>(error), reporter.reports)
    }

    @Test
    fun aParseExceptionIsReportedOnce() = runTest {
        val handlers = listOf(timeoutHandler, envelopeDataMissing("ORD", "GO", ExceptionLayer.Repository))
        val result = guard(handlers) { throw EnvelopeDataMissing() }
        val error = assertIs<ParseException>(result.errorOrNull)
        assertEquals("ORD-R-GO-DF", error.code)
        assertEquals(listOf<SinewException>(error), reporter.reports)
    }

    @Test
    fun aHandlerThatThrowsCountsAsUnmatched() = runTest {
        val brokenTransform = ExceptionHandler({ true }) { error("broken") }
        val brokenMatches = ExceptionHandler({ error("broken") }) { RequestTimeOutException("ORD", ExceptionLayer.Repository, "GO") }
        val result = guard(listOf(brokenTransform, brokenMatches, timeoutHandler)) { throw IllegalStateException("timeout") }
        assertIs<RequestTimeOutException>(result.errorOrNull)
        assertIs<UnexpectedException>(guard(listOf(brokenTransform)) { throw IllegalStateException("x") }.errorOrNull)
    }

    @Test
    fun aReporterThatThrowsDoesNotBreakTheGuard() = runTest {
        CrashReporter.install { error("reporter down") }
        assertIs<UnexpectedException>(guard { throw IllegalArgumentException("bug") }.errorOrNull)
    }

    // Cancellation

    @Test
    fun cancellingTheCallerPropagatesAndIsNeverAFailureOrAReport() = runTest {
        val started = CompletableDeferred<Unit>()
        var afterGuard = false
        val job = launch {
            guard {
                started.complete(Unit)
                awaitCancellation()
            }
            afterGuard = true
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(afterGuard, "the guard returned a value instead of propagating cancellation")
        assertTrue(reporter.reports.isEmpty())
    }

    @Test
    fun cancellingTheCallerStopsTheBlock() = runTest {
        val started = CompletableDeferred<Unit>()
        var finished = false
        val job = launch {
            guard {
                started.complete(Unit)
                delay(1_000)
                finished = true
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertFalse(finished)
    }

    @Test
    fun aCancellationExceptionWhileTheCallerIsActiveIsClassified() = runTest {
        // Some engines wrap a timeout in a CancellationException; rethrowing it would make the timeout vanish.
        val engineTimeout = ExceptionHandler({ it is CancellationException }) { RequestTimeOutException("ORD", ExceptionLayer.Repository, "GO", it) }
        val result = guard(listOf(engineTimeout)) { throw CancellationException("engine timeout") }
        assertIs<RequestTimeOutException>(result.errorOrNull)
        assertIs<UnexpectedException>(guard { throw CancellationException("engine timeout") }.errorOrNull)
    }

    // Use-case guard

    @Test
    fun useCaseGuardPassesADomainExceptionThrough() = runTest {
        val domain = OutOfStockException()
        val result = processResult("ORD", "PO") { throw domain }
        assertSame(domain, result.errorOrNull)
        assertTrue(reporter.reports.isEmpty())
    }

    @Test
    fun useCaseGuardKeepsTheRepositoryCodeFromGetOrThrow() = runTest {
        val repository: Result<Int> = Result.Failure(NotFoundException("ORD", ExceptionLayer.Repository, "GET"))
        val result = processResult("ORD", "PO") { repository.getOrThrow() }
        assertEquals("ORD-R-GET-NTF", result.errorOrNull?.code)
    }

    @Test
    fun useCaseGuardTurnsABugIntoAReportedUseCaseFailure() = runTest {
        val result = processResult("ORD", "PO") { listOf<Int>().first() }
        val error = assertIs<UnexpectedException>(result.errorOrNull)
        assertEquals("ORD-UC-PO-GEN", error.code)
        assertEquals(listOf<SinewException>(error), reporter.reports)
    }

    // CrashReporter

    @Test
    fun installReplacesTheCurrentReporter() {
        val second = RecordingReporter()
        CrashReporter.install(second)
        assertSame(second, CrashReporter.current)
    }
}
