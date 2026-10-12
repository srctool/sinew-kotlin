package com.srctool.sinew.network

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.RequestTimeOutException
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/** The OkHttp predicates, through the real guard: what the engine throws, what the page gets. */
class ConnectivityJvmTest {
    private val reported = mutableListOf<SinewException>()

    @AfterTest
    fun resetReporter() = CrashReporter.install(CrashReporter.None)

    private suspend fun failure(thrown: Throwable): SinewException {
        CrashReporter.install { reported += it }
        val client = Mock(handler = { throw thrown }).client
        return (processApiCall("ORD", "GET") { client.get("a").body<OrderResponse>() } as Result.Failure).error
    }

    @Test
    fun noConnection() = runTest {
        for (e in listOf(UnknownHostException("api.example.com"), ConnectException("refused"), NoRouteToHostException("no route"), SocketException("reset"))) {
            assertIs<NoInternetConnectionException>(failure(e), e.toString())
        }
    }

    @Test
    fun aSocketTimeoutIsATimeout() = runTest {
        assertIs<RequestTimeOutException>(failure(SocketTimeoutException("read timed out")))
    }

    @Test
    fun okHttpsCallTimeoutIsATimeout() = runTest {
        assertIs<RequestTimeOutException>(failure(InterruptedIOException("timeout")))
    }

    @Test
    fun anotherInterruptionIsNotATimeout() = runTest {
        assertIs<UnexpectedException>(failure(InterruptedIOException("thread interrupted")))
    }

    @Test
    fun aPinningFailureSuppressedBehindARefusedConnectionIsNotOffline() = runTest {
        // OkHttp's shape when one address refuses the connection and the other fails the pin.
        val refused = ConnectException("Failed to connect to localhost/[::1]").apply {
            addSuppressed(SSLPeerUnverifiedException("Certificate pinning failure!"))
        }
        assertIs<UnexpectedException>(failure(refused))
    }

    @Test
    fun aTlsFailureCausedByAResetIsStillNotOffline() = runTest {
        val handshake = SSLHandshakeException("Remote host terminated the handshake").apply { initCause(SocketException("Connection reset")) }
        assertIs<UnexpectedException>(failure(handshake))
        assertIs<UnexpectedException>(failure(SocketException("reset").apply { initCause(java.security.cert.CertificateException("expired")) }))
    }

    @Test
    fun aTlsOrPinningFailureIsAReportedUnexpectedExceptionNeverOffline() = runTest {
        for (e in listOf(SSLHandshakeException("bad certificate"), SSLPeerUnverifiedException("Certificate pinning failure!"))) {
            assertIs<UnexpectedException>(failure(e), e.toString())
        }
        assertIs<UnexpectedException>(reported.last())
    }
}
