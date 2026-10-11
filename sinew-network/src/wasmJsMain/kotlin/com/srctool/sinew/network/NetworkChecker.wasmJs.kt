package com.srctool.sinew.network

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual fun platformNetworkChecker(context: PlatformContext): NetworkChecker = BrowserNetworkChecker()

/** `navigator.onLine` at start, then the window's `online` and `offline` events. */
internal class BrowserNetworkChecker : NetworkChecker {
    private val current = MutableStateFlow(if (navigatorOnLine()) NetworkStatus.Online else NetworkStatus.Offline)
    override val status: StateFlow<NetworkStatus> = current.asStateFlow()
    private val onOnline = addWindowListener("online") { current.value = NetworkStatus.Online }
    private val onOffline = addWindowListener("offline") { current.value = NetworkStatus.Offline }

    override fun close() {
        removeWindowListener("online", onOnline)
        removeWindowListener("offline", onOffline)
    }
}

private fun navigatorOnLine(): Boolean = js("navigator.onLine")

/** Adds a listener for [type] on `window` and returns the JS function, to remove it later. */
private fun addWindowListener(type: String, handler: () -> Unit): JsAny =
    js("{ const listener = () => handler(); window.addEventListener(type, listener); return listener; }")

private fun removeWindowListener(type: String, listener: JsAny): Unit = js("window.removeEventListener(type, listener)")
