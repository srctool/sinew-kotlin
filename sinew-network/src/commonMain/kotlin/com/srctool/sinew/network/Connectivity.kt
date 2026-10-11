package com.srctool.sinew.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/** Whether [e] alone is a client-side deadline: Ktor's own timeout types, then the engine's. */
internal fun isTimeout(e: Throwable): Boolean =
    e is HttpRequestTimeoutException || e is ConnectTimeoutException || e is SocketTimeoutException || isEngineTimeout(e)

/** The engine's own timeout types: OkHttp's call timeout, Darwin's `NSURLErrorTimedOut`, nothing more on Js. */
internal expect fun isEngineTimeout(e: Throwable): Boolean

/**
 * Whether [e] alone means the backend couldn't be reached: no network, DNS failure, connection refused or reset.
 * Never a TLS or certificate failure, which is a security event, not "offline".
 */
internal expect fun isNoConnection(e: Throwable): Boolean

/**
 * Whether [e] alone is a TLS or certificate failure, including a pinning mismatch. Anywhere in a failure's chain, it
 * keeps the failure from counting as "no connection": it's a security event, reported as unexpected.
 */
internal expect fun isSecurityFailure(e: Throwable): Boolean
