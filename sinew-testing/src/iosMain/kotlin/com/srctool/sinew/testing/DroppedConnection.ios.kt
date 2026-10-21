package com.srctool.sinew.testing

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost

internal actual fun droppedConnection(): Throwable =
    DarwinHttpRequestException(NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorNetworkConnectionLost, null))
