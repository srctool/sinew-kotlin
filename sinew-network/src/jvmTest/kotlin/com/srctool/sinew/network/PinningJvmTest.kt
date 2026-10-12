package com.srctool.sinew.network

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.testing.FakeTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CertificatePinner
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * `localhost` resolves to ::1 and 127.0.0.1, and the server listens on one of them: OkHttp may fail to connect on one
 * address and fail the pin on the other, then throw the refused connection with the pinning failure suppressed. The
 * pinning failure must still win over "offline".
 *
 * A real HTTPS server whose certificate a private root signed, and an OkHttp client that trusts that root, as a
 * device would trust a public one. Pinning then decides, at the handshake, whether the request is sent at all.
 */
class PinningJvmTest {
    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("pinned root").build()
    private val otherRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("other root").build()
    private val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory())
        start()
    }
    private val trusting = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()

    @AfterTest
    fun stop() {
        server.close()
        CrashReporter.install(CrashReporter.None)
    }

    private fun pinOf(certificate: HeldCertificate) = CertificatePinner.pin(certificate.certificate)

    private fun client(pins: Set<String>) = SinewHttp.client(
        SinewConfig(server.url("/v1").toString(), { "en" }, certificatePins = pins),
        FakeTokenSource(),
        OkHttp,
    ) { engine { config { sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager) } } }

    @Test
    fun aMatchingRootPinIsReached() = runBlocking {
        server.enqueue(MockResponse(body = "ok"))
        assertEquals("ok", client(setOf(pinOf(root))).get("orders").bodyAsText())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aMatchingLeafPinIsReached() = runBlocking {
        server.enqueue(MockResponse(body = "ok"))
        assertEquals("ok", client(setOf(pinOf(leaf))).get("orders").bodyAsText())
    }

    @Test
    fun aBackupPinIsEnough() = runBlocking {
        server.enqueue(MockResponse(body = "ok"))
        assertEquals("ok", client(setOf(pinOf(otherRoot), pinOf(root))).get("orders").bodyAsText())
    }

    @Test
    fun aChainWithNoPinnedKeyFailsBeforeAnyRequestIsSent() = runBlocking {
        val reported = mutableListOf<Any>()
        CrashReporter.install { reported += it }
        val pinned = client(setOf(pinOf(otherRoot)))
        val result = processCall("ORD", "GET", ExceptionLayer.Repository, apiHandlers("ORD", "GET")) {
            pinned.get("orders").status.value // reached the server: a Success, and the test fails
        }
        assertIs<UnexpectedException>((result as Result.Failure).error)
        assertEquals(0, server.requestCount) // no header, no token, no body was sent
        assertEquals(1, reported.size)
    }

    @Test
    fun withoutPinsTheSameServerIsReached() = runBlocking {
        server.enqueue(MockResponse(body = "ok"))
        assertEquals("ok", client(emptySet()).get("orders").bodyAsText())
    }

    @Test
    fun theHandBuiltHelperPinsTheSameWay() = runBlocking {
        val pinned = SinewHttp.client(SinewConfig(server.url("/v1").toString(), { "en" }), FakeTokenSource(), OkHttp) {
            engine {
                config { sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager) }
                pinCertificates("localhost", setOf(pinOf(otherRoot)))
            }
        }
        val result = processCall("ORD", "GET", ExceptionLayer.Repository, apiHandlers("ORD", "GET")) {
            pinned.get("orders").status.value // reached the server: a Success, and the test fails
        }
        assertIs<UnexpectedException>((result as Result.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anEngineThatCannotPinIsRefused() {
        val error = assertFailsWith<IllegalStateException> {
            SinewHttp.client(
                SinewConfig("https://api.example.com", { "en" }, certificatePins = setOf(pinOf(root))),
                FakeTokenSource(),
                MockEngine,
            ) { engine { addHandler { respondOk() } } }
        }
        assertEquals(true, error.message?.contains("can pin"))
    }

    @Test
    fun pinningNothingIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            SinewHttp.client(SinewConfig("https://api.example.com", { "en" }), FakeTokenSource(), OkHttp) {
                engine { pinCertificates("api.example.com", emptySet()) }
            }
        }
    }
}
