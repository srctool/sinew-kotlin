package com.srctool.sinew.testing

import app.cash.turbine.test
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.network.NetworkStatus
import com.srctool.sinew.network.RefreshOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtherFakesTest {
    @Test
    fun tokenSourceRotatesOnEachRefreshAndCountsThem() = runTest {
        val tokens = FakeTokenSource()
        assertEquals("token-1", tokens.accessToken())
        assertEquals(RefreshOutcome.Refreshed, tokens.refresh())
        assertEquals("token-2", tokens.accessToken())
        tokens.refresh()
        assertEquals("token-3", tokens.accessToken())
        assertEquals(2, tokens.refreshes)
    }

    @Test
    fun tokenSourceRunsTheScriptedRefresh() = runTest {
        val tokens = FakeTokenSource()
        val offline = NoInternetConnectionException("AUTH", ExceptionLayer.Repository, "REFRESH")
        tokens.onRefresh = { RefreshOutcome.Unavailable(offline) }
        assertEquals(RefreshOutcome.Unavailable(offline), tokens.refresh())
        assertEquals("token-1", tokens.accessToken(), "an unavailable refresh keeps the session")
        assertEquals(0, tokens.clears)
    }

    @Test
    fun aRejectedRefreshEndsTheSession() = runTest {
        val tokens = FakeTokenSource()
        tokens.onRefresh = { RefreshOutcome.Rejected }
        assertEquals(RefreshOutcome.Rejected, tokens.refresh())
        assertNull(tokens.accessToken())
        assertEquals(1, tokens.clears)
    }

    @Test
    fun tokenSourceClearsAndStartsSignedOutOnRequest() = runTest {
        val tokens = FakeTokenSource()
        tokens.clear()
        assertNull(tokens.accessToken())
        assertEquals(1, tokens.clears)
        assertNull(FakeTokenSource(accessToken = null).accessToken())
        assertEquals("token-8", FakeTokenSource("token-7").rotate())
    }

    @Test
    fun networkCheckerEmitsEachSwitch() = runTest {
        val checker = FakeNetworkChecker()
        checker.status.test {
            assertEquals(NetworkStatus.Online, awaitItem())
            checker.goOffline()
            assertEquals(NetworkStatus.Offline, awaitItem())
            checker.goOnline()
            assertEquals(NetworkStatus.Online, awaitItem())
        }
        assertEquals(NetworkStatus.Offline, FakeNetworkChecker(NetworkStatus.Offline).status.value)
        checker.close()
        assertTrue(checker.closed)
    }

    @Test
    fun crashReporterRecordsAndClears() {
        val reporter = RecordingCrashReporter()
        val error = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "GO")
        reporter.recordNonFatal(error)
        assertEquals(listOf(error), reporter.reports)
        assertEquals(listOf("ORD-R-GO-NIC"), reporter.codes)
        reporter.clear()
        assertEquals(emptyList(), reporter.reports)
    }

    @Test
    fun localizerResolvesFillsArgumentsAndRecordsKeys() {
        val localizer = FakeLocalizer(mapOf("greeting" to "Halo, {name}!"))
        assertEquals("Halo, Ana!", localizer.resolve("greeting", mapOf("name" to "Ana")))
        assertEquals("missing", localizer.resolve("missing", emptyMap()))
        assertEquals(listOf("greeting", "missing"), localizer.requested)
    }

    @Test
    fun withoutEchoAMissingKeyFallsBackToEnglish() {
        val error = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "GO")
        assertNull(FakeLocalizer(echoKeys = false).resolve("noInternet", emptyMap()))
        assertEquals("${error.message} (${error.code})", error.displayMessage(FakeLocalizer(echoKeys = false)))
    }

    @Test
    fun httpLoggerKeepsEachLine() {
        val logger = RecordingHttpLogger()
        logger.log("GET example.com/orders 200 12ms")
        logger.log("POST example.com/orders 201 30ms")
        assertEquals(listOf("GET example.com/orders 200 12ms", "POST example.com/orders 201 30ms"), logger.lines)
    }
}
