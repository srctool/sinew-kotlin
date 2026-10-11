package com.srctool.sinew.network

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.flow.StateFlow

/** Whether the device has a usable network. */
public enum class NetworkStatus { Online, Offline }

/**
 * The device's connectivity, for the UI: an offline banner, a disabled submit button. It never decides a request's
 * error type, which comes from the request's own failure: "connected to Wi-Fi" doesn't mean "the backend is
 * reachable".
 *
 * Monitoring starts on [create] and stops on [close]. Create one per app, at the composition root.
 *
 * | Platform | Online when |
 * |---|---|
 * | Android | the default network has internet and is validated (`NET_CAPABILITY_VALIDATED`) |
 * | iOS | `NWPathMonitor`'s path is satisfied |
 * | JVM | an interface is up and isn't loopback, checked every 5 seconds |
 * | wasm | `navigator.onLine`, then the window's `online` and `offline` events |
 */
public interface NetworkChecker : AutoCloseable {
    /** The current status and its changes. Starts `Online` until the platform reports otherwise. */
    public val status: StateFlow<NetworkStatus>

    /** Whether [status] is `Online` right now. */
    public fun isOnline(): Boolean = status.value == NetworkStatus.Online

    /** Stops monitoring. [status] keeps its last value. */
    override fun close()

    public companion object {
        /** The platform's checker. On Android, [context] carries the `Context`; elsewhere it carries nothing. */
        public fun create(context: PlatformContext): NetworkChecker = platformNetworkChecker(context)
    }
}

internal expect fun platformNetworkChecker(context: PlatformContext): NetworkChecker
