package com.srctool.sinew.network

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut

internal actual fun isEngineTimeout(e: Throwable): Boolean = urlErrorCode(e) == NSURLErrorTimedOut

internal actual fun isNoConnection(e: Throwable): Boolean = urlErrorCode(e) in NoConnectionCodes

/** `NSURLErrorSecureConnectionFailed` (-1200) through the server and client certificate codes (-1206). */
internal actual fun isSecurityFailure(e: Throwable): Boolean =
    urlErrorCode(e)?.let { it in NSURLErrorClientCertificateRequired..NSURLErrorSecureConnectionFailed } == true

private val NoConnectionCodes = setOf(
    NSURLErrorNotConnectedToInternet,
    NSURLErrorCannotFindHost,
    NSURLErrorCannotConnectToHost,
    NSURLErrorNetworkConnectionLost,
    NSURLErrorDNSLookupFailed,
)

/** The `NSURLErrorDomain` code of a Darwin engine failure, or `null`. */
internal fun urlErrorCode(e: Throwable): Long? =
    (e as? DarwinHttpRequestException)?.origin?.takeIf { it.domain == NSURLErrorDomain }?.code
