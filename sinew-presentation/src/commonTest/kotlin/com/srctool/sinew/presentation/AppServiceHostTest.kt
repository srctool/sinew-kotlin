@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.presentation

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.exception.processResult
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.testing.RecordingCrashReporter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Recorder(
    lifetime: ServiceLifetime,
    val name: String,
    val log: MutableList<String>,
    val failInit: Boolean = false,
    val guardInit: Boolean = false,
    val hangInit: Boolean = false,
    val failDestroy: Boolean = false,
) : AppService(lifetime) {
    override suspend fun init() {
        log += "$name.init"
        if (hangInit) awaitCancellation()
        if (guardInit) {
            processResult("MSG", "INIT", ExceptionLayer.Service) { if (failInit) error("catch-up failed") }
        } else if (failInit) {
            error("catch-up failed")
        }
    }

    override fun listen(scope: CoroutineScope) {
        log += "$name.listen"
    }

    override suspend fun destroy() {
        log += "$name.destroy"
        if (failDestroy) error("destroy failed")
    }
}

class AppServiceHostTest {
    private val reporter = RecordingCrashReporter()
    private val log = mutableListOf<String>()
    private val uncaught = mutableListOf<Throwable>()

    @BeforeTest fun install() = CrashReporter.install(reporter)

    @AfterTest fun reset() = CrashReporter.install(CrashReporter.None)

    /** The app scope of a real app: a supervisor, with unhandled errors collected instead of crashing the test. */
    private fun TestScope.appScope() = CoroutineScope(
        backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]) +
            CoroutineExceptionHandler { _, e -> uncaught += e },
    )

    @Test
    fun startAppRunsInitThenListenForAppServicesOnlyInListOrder() = runTest {
        val host = AppServiceHost(appScope(), listOf(
            Recorder(ServiceLifetime.App, "a", log),
            Recorder(ServiceLifetime.Session, "s", log),
            Recorder(ServiceLifetime.App, "b", log),
        ))
        host.startApp()
        runCurrent()
        assertEquals(listOf("a.init", "a.listen", "b.init", "b.listen"), log)
    }

    @Test
    fun aSecondStartAppDoesNothing() = runTest {
        val host = AppServiceHost(appScope(), listOf(Recorder(ServiceLifetime.App, "a", log)))
        host.startApp(); runCurrent()
        host.startApp(); runCurrent()
        assertEquals(listOf("a.init", "a.listen"), log)
    }

    @Test
    fun aSecondStartSessionWhileASessionRunsDoesNothing() = runTest {
        val host = AppServiceHost(appScope(), listOf(Recorder(ServiceLifetime.Session, "s", log)))
        host.startSession(); runCurrent()
        host.startSession(); runCurrent()
        assertEquals(listOf("s.init", "s.listen"), log)
    }

    @Test
    fun stopSessionDestroysOnlySessionServicesInReverseOrderAndANewSessionStartsFresh() = runTest {
        val host = AppServiceHost(appScope(), listOf(
            Recorder(ServiceLifetime.App, "a", log),
            Recorder(ServiceLifetime.Session, "s1", log),
            Recorder(ServiceLifetime.Session, "s2", log),
        ))
        host.startApp(); host.startSession(); runCurrent()
        log.clear()
        host.stopSession()
        assertEquals(listOf("s2.destroy", "s1.destroy"), log)
        log.clear()
        host.startSession(); runCurrent()
        assertEquals(listOf("s1.init", "s1.listen", "s2.init", "s2.listen"), log)
    }

    @Test
    fun stopSessionWithNoSessionDoesNothing() = runTest {
        val host = AppServiceHost(appScope(), listOf(Recorder(ServiceLifetime.Session, "s", log)))
        host.stopSession()
        assertEquals(emptyList(), log)
    }

    @Test
    fun aFailingGuardedInitIsReportedWithTheServiceLayerAndTheServiceStillListens() = runTest {
        val host = AppServiceHost(appScope(), listOf(
            Recorder(ServiceLifetime.App, "a", log, failInit = true, guardInit = true),
            Recorder(ServiceLifetime.App, "b", log),
        ))
        host.startApp(); runCurrent()
        assertEquals(listOf("MSG-SV-INIT-GEN"), reporter.codes)
        assertEquals(listOf("a.init", "a.listen", "b.init", "b.listen"), log)
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun anUnguardedInitFailureDoesNotStopTheServiceListeningOrTheOthersStartingAndSurfacesAsUnhandled() = runTest {
        val host = AppServiceHost(appScope(), listOf(
            Recorder(ServiceLifetime.App, "a", log, failInit = true),
            Recorder(ServiceLifetime.App, "b", log),
        ))
        host.startApp(); runCurrent()
        assertEquals(listOf("a.init", "a.listen", "b.init", "b.listen"), log)
        assertEquals(listOf("catch-up failed"), uncaught.map { it.message })
    }

    @Test
    fun stopSessionCancelsARunningInitThenDestroys() = runTest {
        val host = AppServiceHost(appScope(), listOf(Recorder(ServiceLifetime.Session, "s", log, hangInit = true)))
        host.startSession(); runCurrent()
        assertEquals(listOf("s.init"), log)
        host.stopSession()
        assertEquals(listOf("s.init", "s.destroy"), log)          // no listen: the init never finished
    }

    @Test
    fun aFailingDestroyDoesNotStopTheOthersAndIsRethrownAfterwards() = runTest {
        val host = AppServiceHost(appScope(), listOf(
            Recorder(ServiceLifetime.Session, "s1", log),
            Recorder(ServiceLifetime.Session, "s2", log, failDestroy = true),
        ))
        host.startSession(); runCurrent()
        log.clear()
        val failure = runCatching { host.stopSession() }.exceptionOrNull()
        assertEquals(listOf("s2.destroy", "s1.destroy"), log)
        assertEquals("destroy failed", failure?.message)
    }

    @Test
    fun stopAllDestroysSessionThenAppServicesInReverseOrder() = runTest {
        val host = AppServiceHost(appScope(), listOf(
            Recorder(ServiceLifetime.App, "a1", log),
            Recorder(ServiceLifetime.Session, "s", log),
            Recorder(ServiceLifetime.App, "a2", log),
        ))
        host.startApp(); host.startSession(); runCurrent()
        log.clear()
        host.stopAll()
        assertEquals(listOf("s.destroy", "a2.destroy", "a1.destroy"), log)
    }

    @Test
    fun aServiceThatNeverStartedIsNotDestroyed() = runTest {
        val host = AppServiceHost(appScope(), listOf(Recorder(ServiceLifetime.App, "a", log)))
        host.stopAll()
        assertEquals(emptyList(), log)
    }
}
