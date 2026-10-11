package com.srctool.sinew.network

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

internal actual fun platformNetworkChecker(context: PlatformContext): NetworkChecker = IosNetworkChecker()

/** Watches `NWPathMonitor` on its own serial queue: online when the path is satisfied. */
internal class IosNetworkChecker : NetworkChecker {
    private val current = MutableStateFlow(NetworkStatus.Online)
    override val status: StateFlow<NetworkStatus> = current.asStateFlow()
    private val monitor = nw_path_monitor_create()

    /** Whether the monitor reported at least once: tests wait for it. */
    internal val reported = MutableStateFlow(false)

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            current.value = if (nw_path_get_status(path) == nw_path_status_satisfied) NetworkStatus.Online else NetworkStatus.Offline
            reported.value = true
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.srctool.sinew.network.checker", null))
        nw_path_monitor_start(monitor)
    }

    override fun close() {
        nw_path_monitor_cancel(monitor)
    }
}
