package com.srctool.sinew.network

internal actual fun isEngineTimeout(e: Throwable): Boolean = false

/** The browser handles TLS itself and reports a certificate failure like any other `fetch` failure. */
internal actual fun isSecurityFailure(e: Throwable): Boolean = false

/**
 * The browser's `fetch` reports every network failure as the same `TypeError`, so offline is told apart only by
 * `navigator.onLine`: a failure that isn't an HTTP status, while the browser is offline.
 */
internal actual fun isNoConnection(e: Throwable): Boolean = isNoConnection(e, browserOnline())

internal fun isNoConnection(e: Throwable, online: Boolean): Boolean = !online && e.statusError() == null

private fun browserOnline(): Boolean = js("navigator.onLine")
