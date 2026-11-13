package com.srctool.sinew.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.cancellation.CancellationException

/** How long an [AppService] lives. */
public enum class ServiceLifetime {
    /** From app start to process death: started by [AppServiceHost.startApp]. */
    App,

    /** From sign-in to sign-out: started by [AppServiceHost.startSession], stopped by [AppServiceHost.stopSession]. */
    Session,
}

/**
 * The second kind of driver, beside a page handler: work with the app's or the signed-in session's lifetime and no UI
 * (a purchase from the store, a pub/sub message, a push). It calls use cases and folds their `Result`, and writes to
 * repositories that pages observe. Name it for what it does, plus `Service`: `BillingService`.
 *
 * A service guards its own steps with `processResult(module, function, ExceptionLayer.Service) { … }`, so a bug in
 * one is reported with an `SV` code (`MSG-SV-INIT-GEN`). Whatever a step still throws never stops the other services
 * or this one from listening (see [AppServiceHost]).
 *
 * @property lifetime Whether the app or the signed-in session owns this service.
 */
public abstract class AppService(public val lifetime: ServiceLifetime) {
    /**
     * Catches up on what happened while the app wasn't running (an unacknowledged purchase, unread messages), once per
     * start and before [listen]. The use cases it calls must be idempotent.
     */
    public open suspend fun init() {}

    /**
     * Subscribes to the sources (a store, a socket, a push). Launch the collectors in [scope], which is cancelled when
     * the service stops.
     */
    public abstract fun listen(scope: CoroutineScope)

    /** Stops listening and releases what the service holds. A session service clears what it holds for that user. */
    public open suspend fun destroy() {}
}

/**
 * Starts and stops [services] with the app and the signed-in session. Call its functions from one thread (the main
 * one): the app's start-up and the session owner's sign-in and sign-out.
 *
 * Each service is isolated: a step that throws is surfaced as an unhandled error (in development and in crash
 * reports, like any bug) and the service still listens, and the others still start. `startApp` and `startSession`
 * return at once; the services start in [appScope] in list order, each one's [AppService.init] before its
 * [AppService.listen].
 *
 * @param appScope The app's scope: `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, created once at app start.
 * @param services Every service, in the order they start. The app decides which services exist.
 */
public class AppServiceHost(private val appScope: CoroutineScope, private val services: List<AppService>) {
    private var appJob: Job? = null
    private val appStarted = mutableListOf<AppService>()
    private var sessionJob: Job? = null
    private val sessionStarted = mutableListOf<AppService>()

    /** Runs `init`, then `listen`, for every [ServiceLifetime.App] service, in list order. A second call does nothing. */
    public fun startApp() {
        if (appJob != null) return
        appJob = launchStart(ServiceLifetime.App, appStarted)
    }

    /** The same for [ServiceLifetime.Session] services, after sign-in. A second call while a session runs does nothing. */
    public fun startSession() {
        if (sessionJob != null) return
        sessionJob = launchStart(ServiceLifetime.Session, sessionStarted)
    }

    /**
     * Cancels the session's work (including an `init` still running), then calls `destroy` on the session services
     * that started, in reverse order. Does nothing when no session runs. A `destroy` that throws doesn't stop the
     * others; the first failure is rethrown after all of them ran.
     */
    public suspend fun stopSession() {
        val job = sessionJob ?: return
        sessionJob = null
        job.cancelAndJoin()
        destroyInReverse(sessionStarted)
    }

    /** Stops the session, then destroys the app services in reverse order: shutdown and tests. */
    public suspend fun stopAll() {
        var failure: Throwable? = null
        try {
            stopSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        }
        val job = appJob
        appJob = null
        job?.cancelAndJoin()
        try {
            destroyInReverse(appStarted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        failure?.let { throw it }
    }

    private fun launchStart(lifetime: ServiceLifetime, started: MutableList<AppService>): Job = appScope.launch {
        // A supervisor, so one service's failing listener never cancels the others'.
        supervisorScope {
            for (service in services) {
                if (service.lifetime != lifetime) continue
                started += service
                guard { service.init() }
                guard { service.listen(this@supervisorScope) }
            }
        }
    }

    /**
     * Runs one step of a service. A throw becomes an unhandled error of its own child coroutine: it's reported like any
     * bug, and in this supervisor nothing else ends. Cancellation passes through, because it isn't a failure.
     */
    private suspend fun CoroutineScope.guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            launch { throw e }
        }
    }

    private suspend fun destroyInReverse(started: MutableList<AppService>) {
        var failure: Throwable? = null
        for (service in started.asReversed()) {
            try {
                service.destroy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        started.clear()
        failure?.let { throw it }
    }
}
