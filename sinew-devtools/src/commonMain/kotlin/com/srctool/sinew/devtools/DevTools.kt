package com.srctool.sinew.devtools

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Devtools' capture, shared by the network hooks, the crash reporter and the dashboard. Everything here is in memory
 * only. Production builds depend on `sinew-devtools-noop` instead, where every call does nothing.
 */
@OptIn(ExperimentalAtomicApi::class)
public object DevTools {
    /** The Network Monitor's records. */
    public val network: NetworkRecorder = NetworkRecorder()

    /** The Exception Log's records. */
    public val exceptions: ExceptionRecorder = ExceptionRecorder()

    /** Records an uncaught coroutine failure in the Exception Log. Add it to the app's root scopes. */
    public val coroutineHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, error -> exceptions.record(error, ExceptionSource.Uncaught) }

    private val capturing = AtomicBoolean(false)

    /**
     * Records uncaught errors in the Exception Log, through the platform's hook: the default uncaught-exception
     * handler on Android and the JVM, the unhandled-exception hook on iOS, the window's `error` and
     * `unhandledrejection` events on the web. The previous handler still runs after it. Calling it again does nothing.
     */
    public fun captureUncaughtErrors() {
        if (capturing.compareAndSet(expectedValue = false, newValue = true)) {
            installUncaughtHook { exceptions.record(it, ExceptionSource.Uncaught) }
        }
    }

    /** Forgets what belongs to the signed-in user: the network records. Call it from the app's sign-out. */
    public fun clearUserData() {
        network.clear()
    }
}

/**
 * Calls [onError] for every uncaught error, then the handler that was installed before. A failure in [onError] never
 * stops the previous handler.
 */
internal expect fun installUncaughtHook(onError: (Throwable) -> Unit)
