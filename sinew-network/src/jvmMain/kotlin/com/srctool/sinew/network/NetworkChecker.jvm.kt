package com.srctool.sinew.network

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal actual fun platformNetworkChecker(context: PlatformContext): NetworkChecker =
    JvmNetworkChecker(::anyInterfaceUp, 5.seconds, Dispatchers.IO)

/** The JVM has no change events: it checks for an interface that's up and not loopback every [every]. */
internal class JvmNetworkChecker(
    private val online: () -> Boolean,
    private val every: Duration,
    context: CoroutineContext,
) : NetworkChecker {
    private val current = MutableStateFlow(NetworkStatus.Online)
    override val status: StateFlow<NetworkStatus> = current.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + context)

    init {
        scope.launch {
            while (isActive) {
                current.value = if (online()) NetworkStatus.Online else NetworkStatus.Offline
                delay(every)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

private fun anyInterfaceUp(): Boolean = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { it.isUp && !it.isLoopback }
} catch (_: java.net.SocketException) {
    false
}
