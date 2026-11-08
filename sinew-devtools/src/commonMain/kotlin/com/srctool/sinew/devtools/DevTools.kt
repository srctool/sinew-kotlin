package com.srctool.sinew.devtools

import com.srctool.sinew.network.SinewHttpHooks
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** The active fault rules, in memory only. */
    public val faults: FaultRules = FaultRules()

    /** The API Host page's choice. */
    public val hosts: HostOverride = HostOverride()

    private val hooks = DevToolsHttpHooks(network, faults, hosts)

    /**
     * The hooks to build each client with, from the platform entry point:
     * `SinewHttp.client(config, tokens, hooks = DevTools.httpHooks)`. They record every exchange in [network], apply
     * [faults] and the [hosts] override inside the auth layer, and let [replay] and [expireAccessToken] reach the client.
     */
    public val httpHooks: SinewHttpHooks get() = hooks

    /** Whether [record] can be sent again now: it kept everything it needs, and its client is still open. */
    public fun canReplay(record: NetworkRecord): Boolean = hooks.canReplay(record)

    /**
     * Sends [record]'s request again through the client that sent it, with a fresh token, and records the result as
     * a new record marked as a replay. Throws `IllegalArgumentException` for a record that isn't replayable and
     * `IllegalStateException` when its client is closed; a failed response is in the new record, not thrown.
     */
    public suspend fun replay(record: NetworkRecord) {
        hooks.replay(record)
    }

    /** "Expire access token": the next request of each client that carries a token sends an invalid one, so the real refresh runs. */
    public fun expireAccessToken() {
        hooks.expireAccessToken()
    }

    /** Records an uncaught coroutine failure in the Exception Log. Add it to the app's root scopes. */
    public val coroutineHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, error -> exceptions.record(error, ExceptionSource.Uncaught) }

    private val capturing = AtomicBoolean(false)

    private val _isOpen = MutableStateFlow(false)

    /** Whether the dashboard is open. */
    public val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    /**
     * Opens the dashboard, from any trigger: the app's own gesture with `CustomLauncher`, or `DevToolsMenuItem`'s row.
     * It does nothing until `SinewDevTools` is on screen, and in the no-op artifact.
     */
    public fun open() {
        _isOpen.value = true
    }

    /** Closes the dashboard. */
    public fun close() {
        _isOpen.value = false
    }

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
