package com.srctool.sinew.network

import com.srctool.sinew.testing.FakeTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Each Sinew plugin on a plain HttpClient, without the preset: alone, and in any install order. */
class StandalonePluginsTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun plain(handler: MockRequestHandler = { respond("ok") }, block: HttpClientConfig<MockEngineConfig>.() -> Unit) =
        HttpClient(MockEngine) {
            engine { addHandler { requests += it; handler(it) } }
            block()
        }

    @Test
    fun sinewAuthAloneRefreshesAndReplays() = runTest {
        val tokens = FakeTokenSource("token-1")
        val client = plain({ if (it.headers[HttpHeaders.Authorization] == "Bearer token-2") respond("ok") else respond("", HttpStatusCode.Unauthorized) }) {
            install(SinewAuth) {
                tokenSource = tokens
                baseUrl = "https://api.example.com/v1"
            }
        }
        assertEquals("ok", client.get("https://api.example.com/v1/orders").bodyAsText())
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun sinewAuthWorksWithKtorsOwnRedirect() = runTest {
        val tokens = FakeTokenSource("token-1")
        val client = plain({
            when (it.url.toString()) {
                "https://api.example.com/v1/a" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/v1/b"))
                "https://api.example.com/v1/b" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.net/c"))
                else -> respond("ok")
            }
        }) {
            install(SinewAuth) {
                tokenSource = tokens
                baseUrl = "https://api.example.com/v1"
            }
        }
        client.get("https://api.example.com/v1/a")
        assertEquals(listOf("Bearer token-1", "Bearer token-1", null), requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun sinewAuthNeedsItsSettings() = runTest {
        assertFailsWith<IllegalStateException> { plain { install(SinewAuth) { baseUrl = "https://api.example.com" } } }
        val noBase = plain { install(SinewAuth) { tokenSource = FakeTokenSource() } }
        val error = assertFailsWith<IllegalStateException> { noBase.get("https://api.example.com/a") }
        assertTrue("sinewBackend" in error.message.orEmpty())
        assertFailsWith<IllegalArgumentException> {
            plain {
                install(SinewAuth) {
                    tokenSource = FakeTokenSource()
                    baseUrl = "https://api.example.com"
                    anonymousPaths = setOf("auth/login")
                }
            }
        }
    }

    @Test
    fun sinewBackendResolvesRelativePathsUnderItsPath() = runTest {
        val client = plain { sinewBackend("https://api.example.com/v1") }
        client.get("orders/7")
        assertEquals("https://api.example.com/v1/orders/7", requests.single().url.toString())
    }

    @Test
    fun sinewAuthReadsTheBackendEvenWhenInstalledFirst() = runTest {
        val tokens = FakeTokenSource("token-1")
        val client = plain {
            install(SinewAuth) {
                tokenSource = tokens
                anonymousPaths = setOf("/auth/login")
            }
            sinewBackend("https://api.example.com/v1")
        }
        client.get("orders")
        client.get("auth/login")
        client.get("https://cdn.example.net/a.png")
        assertEquals(listOf("Bearer token-1", null, null), requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun sinewAuthsOwnBaseUrlWinsOverTheBackend() = runTest {
        val client = plain {
            sinewBackend("https://api.example.com/v1")
            install(SinewAuth) {
                tokenSource = FakeTokenSource("token-1")
                baseUrl = "https://auth.example.com"
            }
        }
        client.get("orders")
        client.get("https://auth.example.com/me")
        assertEquals(listOf(null, "Bearer token-1"), requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun aBlankBackendFails() {
        assertFailsWith<IllegalArgumentException> { plain { sinewBackend(" ") } }
    }

    @Test
    fun theErrorBodyValidatorSeesTheResponseAfterARefreshWhateverTheOrder() = runTest {
        val tokens = FakeTokenSource("token-1")
        val client = plain({ if (it.headers[HttpHeaders.Authorization] == "Bearer token-2") respond("ok") else respond("", HttpStatusCode.Unauthorized) }) {
            expectSuccess = false
            HttpResponseValidator { sinewErrorBody() }                            // declared before SinewAuth
            install(SinewAuth) {
                tokenSource = tokens
                baseUrl = "https://api.example.com"
            }
        }
        assertEquals("ok", client.get("https://api.example.com/orders").bodyAsText())
    }

    @Test
    fun theErrorBodyValidatorAloneThrowsTheReadError() = runTest {
        val client = plain({ respond("""{"message":"Gone."}""", HttpStatusCode.Gone) }) {
            expectSuccess = false
            HttpResponseValidator { sinewErrorBody() }
        }
        val error = assertFailsWith<HttpStatusError> { client.get("https://x.example/a") }
        assertEquals(ErrorBody("Gone.", null), error.errorBody)
    }

    @Test
    fun sinewHeadersInstalledAfterContentNegotiationStillLetsTheRequestsAcceptWin() = runTest {
        val client = plain {
            install(ContentNegotiation) { json(SinewJson) }
            install(SinewHeaders) { locale = { "id" } }
        }
        client.get("https://x.example/a") { header(HttpHeaders.Accept, "text/csv") }
        client.get("https://x.example/b")
        assertEquals(listOf("text/csv"), requests[0].headers.getAll(HttpHeaders.Accept))
        assertEquals(listOf("application/json"), requests[1].headers.getAll(HttpHeaders.Accept))
        assertEquals("id", requests[1].headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun sinewTimeoutsAloneUsesItsDefaults() = runTest {
        val client = plain {
            install(HttpTimeout)
            install(SinewTimeouts)
        }
        client.get("https://x.example/a")
        val timeout = requests.single().getCapabilityOrNull(HttpTimeoutCapability)!!
        assertEquals(15_000L, timeout.connectTimeoutMillis)
        assertEquals(30_000L, timeout.requestTimeoutMillis)
    }

    @Test
    fun sinewTimeoutsWithoutHttpTimeoutSaysSo() = runTest {
        val client = plain { install(SinewTimeouts) }
        val error = assertFailsWith<IllegalStateException> { client.get("https://x.example/a") }
        assertTrue("install(HttpTimeout)" in error.message.orEmpty())
    }

    @Test
    fun sinewTimeoutsRejectsANonPositiveTimeout() {
        assertFailsWith<IllegalArgumentException> { plain { install(SinewTimeouts) { read = 0.seconds } } }
    }

    @Test
    fun sinewRedirectNeedsKtorsRedirectOff() {
        val error = assertFailsWith<IllegalStateException> { plain { install(SinewRedirect) } }
        assertTrue("followRedirects = false" in error.message.orEmpty())
    }

    @Test
    fun sinewRedirectTakesItsHopLimit() = runTest {
        val client = plain({ respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "again")) }) {
            followRedirects = false
            install(SinewRedirect) { maxHops = 2 }
        }
        assertEquals(HttpStatusCode.Found, client.get("https://x.example/a").status)
        assertEquals(3, requests.size)
    }

    @Test
    fun sinewLogAlone() = runTest {
        val lines = mutableListOf<String>()
        val client = plain { install(SinewLog) { logger = HttpLogger { lines += it } } }
        client.get("https://x.example/a?secret=1")
        assertTrue(lines.single().startsWith("GET x.example/a → 200 "), lines.single())
    }

    @Test
    fun theWholeSetInstalledByHandMatchesThePreset() = runTest {
        val tokens = FakeTokenSource("token-1")
        val lines = mutableListOf<String>()
        val client = plain({ if (it.headers[HttpHeaders.Authorization] == "Bearer token-1") respond("ok") else respond("", HttpStatusCode.Unauthorized) }) {
            expectSuccess = false
            followRedirects = false
            install(SinewLog) { logger = HttpLogger { lines += it } }               // deliberately first
            install(SinewAuth) {
                tokenSource = tokens
                baseUrl = "https://api.example.com/v1"
            }
            install(SinewRedirect)
            install(HttpTimeout)
            install(SinewTimeouts)
            install(SinewHeaders) { locale = { "en" } }
            HttpResponseValidator { sinewErrorBody() }
        }
        assertEquals("ok", client.get("https://api.example.com/v1/orders").bodyAsText())
        assertEquals("en", requests.single().headers[HttpHeaders.AcceptLanguage])
        assertEquals(1, lines.size)
    }
}
