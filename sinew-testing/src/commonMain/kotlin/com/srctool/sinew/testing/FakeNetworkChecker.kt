package com.srctool.sinew.testing

import com.srctool.sinew.network.NetworkChecker
import com.srctool.sinew.network.NetworkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A [NetworkChecker] a test switches by hand. */
public class FakeNetworkChecker(initial: NetworkStatus = NetworkStatus.Online) : NetworkChecker {
    private val current = MutableStateFlow(initial)
    override val status: StateFlow<NetworkStatus> = current.asStateFlow()

    /** Whether [close] was called. */
    public var closed: Boolean = false
        private set

    /** Reports the device offline. */
    public fun goOffline() {
        current.value = NetworkStatus.Offline
    }

    /** Reports the device online. */
    public fun goOnline() {
        current.value = NetworkStatus.Online
    }

    override fun close() {
        closed = true
    }
}
