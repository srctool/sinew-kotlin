package com.srctool.sinew.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual fun platformNetworkChecker(context: PlatformContext): NetworkChecker = AndroidNetworkChecker(context.context)

/** Watches the default network: online when it has internet and Android validated it (a captive portal isn't). */
internal class AndroidNetworkChecker(context: Context) : NetworkChecker {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val current = MutableStateFlow(statusOf(manager.getNetworkCapabilities(manager.activeNetwork)))
    override val status: StateFlow<NetworkStatus> = current.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            current.value = statusOf(capabilities)
        }

        override fun onLost(network: Network) {
            current.value = NetworkStatus.Offline
        }
    }

    init {
        manager.registerDefaultNetworkCallback(callback)
    }

    override fun close() {
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // already closed
        }
    }
}

private fun statusOf(capabilities: NetworkCapabilities?): NetworkStatus = networkStatus(
    hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
    validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
)

internal fun networkStatus(hasInternet: Boolean, validated: Boolean): NetworkStatus =
    if (hasInternet && validated) NetworkStatus.Online else NetworkStatus.Offline
