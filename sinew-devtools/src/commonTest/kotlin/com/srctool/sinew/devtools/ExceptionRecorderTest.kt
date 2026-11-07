package com.srctool.sinew.devtools

import com.srctool.sinew.exception.ApiErrorException
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.ParseException
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.testing.RecordingCrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ExceptionRecorderTest {
    private class FakeClock(var now: Instant = Instant.parse("2026-11-07T10:00:00Z")) : Clock {
        override fun now(): Instant = now
        fun advance(by: Duration) {
            now += by
        }
    }

    private val clock = FakeClock()
    private val recorder = ExceptionRecorder(clock = clock)
    private fun offline(function: String = "list") = NoInternetConnectionException("ORD", ExceptionLayer.Repository, function)

    @AfterTest
    fun reset() = DevTools.exceptions.clear()

    @Test
    fun repeatsWithinTheWindowAreMergedWithACount() {
        val start = clock.now
        recorder.record(offline())
        clock.advance(1.seconds)
        recorder.record(offline())
        val row = recorder.records.value.single()
        assertEquals(2, row.count)
        assertEquals(start, row.firstAt)
        assertEquals(start + 1.seconds, row.lastAt)
    }

    @Test
    fun theWindowRunsFromTheLastRepeat() {
        recorder.record(offline())
        clock.advance(1500.milliseconds)
        recorder.record(offline())
        clock.advance(1500.milliseconds)
        recorder.record(offline())                          // 3 s after the first, 1.5 s after the last
        assertEquals(3, recorder.records.value.single().count)
    }

    @Test
    fun aRepeatAfterTheWindowIsANewRow() {
        recorder.record(offline())
        clock.advance(2001.milliseconds)
        recorder.record(offline())
        assertEquals(listOf(1, 1), recorder.records.value.map { it.count })
        clock.advance(2.seconds)
        recorder.record(offline())                          // exactly at the window: still a repeat
        assertEquals(listOf(2, 1), recorder.records.value.map { it.count })
    }

    @Test
    fun differentFailuresAreSeparateRows() {
        recorder.record(offline("list"))
        recorder.record(offline("detail"))
        recorder.record(offline("list"), ExceptionSource.Uncaught)
        assertEquals(3, recorder.records.value.size)
    }

    @Test
    fun aMergedRowMovesToTheTop() {
        recorder.record(offline("list"))
        recorder.record(offline("detail"))
        recorder.record(offline("list"))
        assertEquals(listOf(offline("list").code, offline("detail").code), recorder.records.value.map { it.code })
    }

    @Test
    fun theCapacityDropsTheOldestRow() {
        val small = ExceptionRecorder(capacity = 2, clock = clock)
        listOf("a", "b", "c").forEach { small.record(offline(it)) }
        assertEquals(listOf(offline("c").code, offline("b").code), small.records.value.map { it.code })
    }

    @Test
    fun theDefaultsAreAsDesigned() {
        assertEquals(50, ExceptionRecorder.DEFAULT_CAPACITY)
        assertEquals(2.seconds, ExceptionRecorder.DEFAULT_DEDUPE_WINDOW)
        assertEquals(200, NetworkRecorder.DEFAULT_CAPACITY)
        assertFailsWith<IllegalArgumentException> { ExceptionRecorder(capacity = 0) }
    }

    @Test
    fun aRowKeepsTheKeyTheServerTextOrTheMessage() {
        val local = offline()
        recorder.record(local)
        val row = recorder.records.value.single()
        assertEquals((local.errorMessage as ErrorMessage.Local).key, row.message)
        assertEquals("NoInternetConnectionException", row.type)
        assertEquals(local.code, row.code)
        assertEquals(ExceptionSource.Reported, row.source)

        recorder.record(ApiErrorException("ORD", ExceptionLayer.Repository, "create", ErrorMessage.Server("Stok habis")))
        assertEquals("Stok habis", recorder.records.value.first().message)

        recorder.record(IllegalStateException("plain"))
        assertEquals("plain", recorder.records.value.first().message)
        assertEquals(null, recorder.records.value.first().code)
    }

    @Test
    fun theMessageAndStackTraceAreRedacted() {
        recorder.record(IllegalStateException("GET https://api.test/me?token=abc123 failed, Authorization: Bearer xyz789"))
        val row = recorder.records.value.single()
        for (stored in listOf(row.message, row.stackTrace, row.asText(), row.toString())) {
            assertFalse("abc123" in stored, stored)
            assertFalse("xyz789" in stored, stored)
        }
        assertTrue("https://api.test/me" in row.message)
    }

    @Test
    fun aCauseIsRedactedToo() {
        recorder.record(ParseException("ORD", ExceptionLayer.Repository, "list", cause = IllegalArgumentException("""{"password":"hunter2"}""")))
        assertFalse("hunter2" in recorder.records.value.single().stackTrace)
    }

    @Test
    fun asTextIsABugReport() {
        val error = offline()
        recorder.record(error)
        recorder.record(error)
        val text = recorder.records.value.single().asText()
        assertTrue(text.startsWith("NoInternetConnectionException ${error.code} ×2\n"), text)
        assertTrue("Reported · first 2026-11-07T10:00:00Z · last 2026-11-07T10:00:00Z" in text, text)
    }

    @Test
    fun theWrappedReporterReceivesEveryReport() {
        val real = RecordingCrashReporter()
        val reporter = DevToolsCrashReporter(wrapping = real, recorder = recorder)
        val error = offline()
        reporter.recordNonFatal(error)
        reporter.recordNonFatal(error)
        assertEquals(listOf(error, error), real.reports)                  // unchanged, and every one of them
        assertEquals(2, recorder.records.value.single().count)            // while the log merges them
    }

    @Test
    fun theCrashReporterUsesDevToolsByDefault() {
        DevToolsCrashReporter(wrapping = RecordingCrashReporter()).recordNonFatal(offline())
        assertEquals(1, DevTools.exceptions.records.value.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theCoroutineHandlerRecordsAnUncaughtFailure() {
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher() + DevTools.coroutineHandler).launch { error("boom") }
        val row = DevTools.exceptions.records.value.single()
        assertEquals("IllegalStateException", row.type)
        assertEquals("boom", row.message)
        assertEquals(ExceptionSource.Uncaught, row.source)
    }
}
