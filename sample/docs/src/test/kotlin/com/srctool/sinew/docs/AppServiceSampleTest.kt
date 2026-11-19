@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.docs

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.testing.RecordingCrashReporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The usage docs' app service: catches up in init, then listens, and a failing step reports an SV code. */
class AppServiceSampleTest {
    private val reporter = RecordingCrashReporter()

    @BeforeTest fun install() = CrashReporter.install(reporter)

    @AfterTest fun reset() = CrashReporter.install(CrashReporter.None)

    private class FakeStore(val pending: List<Purchase>, override val arrivals: Flow<Purchase> = emptyFlow()) : PurchaseStore {
        override suspend fun unacknowledged() = pending
    }

    @Test
    fun initAcknowledgesWhatArrivedWhileTheAppWasNotRunning() = runTest {
        val seen = mutableListOf<String>()
        val service = BillingService(FakeStore(listOf(Purchase("a"), Purchase("b")))) { seen += it.id }
        service.init()
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun listenAcknowledgesEachNewPurchase() = runTest {
        val seen = mutableListOf<String>()
        val service = BillingService(FakeStore(emptyList(), flowOf(Purchase("c"), Purchase("d")))) { seen += it.id }
        service.listen(backgroundScope)
        runCurrent()                              // advanceUntilIdle() would skip background work
        assertEquals(listOf("c", "d"), seen)
    }

    @Test
    fun aBugInInitIsReportedWithTheServiceLayer() = runTest {
        val service = BillingService(FakeStore(listOf(Purchase("a")))) { error("store down") }
        service.init()
        assertEquals(listOf("BIL-SV-INIT-GEN"), reporter.codes)
    }

    @Test
    fun theHostStartsAndStopsTheServices() = runTest {
        val seen = mutableListOf<String>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val lifecycle = AppLifecycle(
            com.srctool.sinew.presentation.AppServiceHost(scope, listOf(BillingService(FakeStore(listOf(Purchase("a")))) { seen += it.id })),
        )
        lifecycle.launched()
        advanceUntilIdle()
        assertEquals(listOf("a"), seen)
        lifecycle.signedOut()                     // nothing to stop: no session service, so no failure
    }
}
