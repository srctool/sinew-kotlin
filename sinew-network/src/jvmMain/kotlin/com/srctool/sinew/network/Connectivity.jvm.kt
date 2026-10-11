package com.srctool.sinew.network

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** OkHttp's whole-call timeout: a plain `InterruptedIOException("timeout")`. Ktor's socket timeouts are common types. */
internal actual fun isEngineTimeout(e: Throwable): Boolean =
    e is InterruptedIOException && e !is SocketTimeoutException && e.message == "timeout"

internal actual fun isNoConnection(e: Throwable): Boolean =
    e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SocketException

internal actual fun isSecurityFailure(e: Throwable): Boolean = e is SSLException || e is CertificateException
