package com.srctool.sinew.network

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Darwin predicates: `NSURLErrorDomain` codes. */
class ConnectivityIosTest {
    private fun darwin(code: Long, domain: String = NSURLErrorDomain!!) =
        DarwinHttpRequestException(NSError.errorWithDomain(domain, code, null))

    @Test
    fun noConnectionCodes() {
        for (code in listOf(NSURLErrorNotConnectedToInternet, NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost, NSURLErrorDNSLookupFailed)) {
            assertTrue(isNoConnectionFailure(darwin(code)), "code $code")
            assertFalse(isTimeoutFailure(darwin(code)), "code $code")
        }
    }

    @Test
    fun aTimedOutCodeIsATimeout() {
        assertTrue(isTimeoutFailure(darwin(NSURLErrorTimedOut)))
        assertFalse(isNoConnectionFailure(darwin(NSURLErrorTimedOut)))
    }

    @Test
    fun certificateAndTlsCodesAreNeither() {
        for (code in listOf(NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasBadDate, NSURLErrorSecureConnectionFailed)) {
            assertFalse(isNoConnectionFailure(darwin(code)), "code $code")
            assertFalse(isTimeoutFailure(darwin(code)), "code $code")
            assertTrue(isSecurityFailure(darwin(code)), "code $code")
        }
        assertFalse(isSecurityFailure(darwin(NSURLErrorNotConnectedToInternet)))
    }

    @Test
    fun anotherDomainIsNeither() {
        assertFalse(isNoConnectionFailure(darwin(NSURLErrorNotConnectedToInternet, domain = "NSPOSIXErrorDomain")))
    }
}
