@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.srctool.sinew.network

import com.srctool.sinew.testing.FakeTokenSource
import com.srctool.sinew.testing.mockHttp
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Hooks that answer with [faults] by path, override the host with [host], and keep every exchange. */
private class TestHooks(
    val faults: MutableMap<String, ArrayDeque<FaultDecision>> = mutableMapOf(),
    var host: String? = null,
) : SinewHttpHooks {
    val exchanges = mutableListOf<HttpExchange>()
    val clients = mutableListOf<HttpClient>()

    override fun attached(client: HttpClient) {
        clients += client
    }

    override fun detached(client: HttpClient) {
        clients -= client
    }

    override fun hostOverride(): String? = host

    override suspend fun fault(method: String, url: Url): FaultDecision? = faults[url.encodedPath]?.removeFirstOrNull()

    override fun record(exchange: HttpExchange) {
        exchanges += exchange
    }

    fun failNext(path: String, decision: FaultDecision) {
        faults.getOrPut(path) { ArrayDeque() } += decision
    }
}

private val Marked = AttributeKey<Unit>("TestMarked")

class SinewHooksTest {
    private val config = SinewConfig(
        baseUrl = "https://test.local/v1/",
        locale = { "en" },
        defaultHeaders = { mapOf("X-Device-Id" to "device-7") },
    )

    @Test
    fun theClientIsHandedToTheHooksOnceAndTakenBackOnClose() = runTest {
        val hooks = TestHooks()
        val http = mockHttp(config, hooks = hooks) { }
        assertEquals(listOf(http.client), hooks.clients)
        http.client.close()
        assertEquals(emptyList(), hooks.clients)                          // closing tells the hooks
    }

    @Test
    fun anExchangeIsRecordedWithItsRawValuesAndTheBodyStillReads() = runTest {
        val hooks = TestHooks()
        val http = mockHttp(config, hooks = hooks) {
            on(HttpMethod.Post, "/v1/orders").respond(201, """{"id":7}""")
        }
        val response = http.client.post("orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ana"}""")
            attributes.put(Marked, Unit)
        }
        assertEquals("""{"id":7}""", response.bodyAsText())                 // recording didn't consume the body
        val exchange = hooks.exchanges.single()
        assertSame(http.client, exchange.client)
        assertEquals("POST", exchange.method)
        assertEquals("https://test.local/v1/orders", exchange.url)
        assertEquals(exchange.url, exchange.requestedUrl)
        assertEquals(listOf("Bearer token-1"), exchange.requestHeaders[HttpHeaders.Authorization])   // inside the auth layer
        assertEquals(listOf("device-7"), exchange.requestHeaders["X-Device-Id"])
        assertTrue(exchange.requestHeaders.getValue(HttpHeaders.ContentType).single().startsWith("application/json"))
        assertEquals("""{"name":"ana"}""", exchange.requestBody?.decodeToString())
        assertFalse(exchange.requestBodyStreamed)
        assertEquals(201, exchange.status)
        assertEquals("""{"id":7}""", exchange.responseBody?.decodeToString())
        assertEquals(setOf("X-Device-Id"), exchange.defaultHeaderNames)
        assertTrue(exchange.attributes.contains(Marked))
        assertNull(exchange.error)
    }

    @Test
    fun aStreamedRequestBodyIsFlagged() = runTest {
        val hooks = TestHooks()
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Post, "/v1/upload").respond(204) }
        http.client.post("upload") { setBody(ByteReadChannel(byteArrayOf(1, 2, 3))) }
        val exchange = hooks.exchanges.single()
        assertTrue(exchange.requestBodyStreamed)
        assertNull(exchange.requestBody)
    }

    @Test
    fun aForced401RunsTheRealRefreshOnce() = runTest {
        val hooks = TestHooks()
        val tokens = FakeTokenSource()
        val http = mockHttp(config, tokens, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200, """{"ok":true}""") }
        hooks.failNext("/v1/orders", FaultDecision(status = 401))
        assertEquals("""{"ok":true}""", http.client.get("orders").bodyAsText())
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf(401, 200), hooks.exchanges.map { it.status })
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), hooks.exchanges.map { it.requestHeaders.getValue(HttpHeaders.Authorization).single() })
        assertEquals(1, http.requests.size)                                     // the fault never reached the network
    }

    @Test
    fun aForcedStatusAnswersWithItsBodyWithoutTheNetwork() = runTest {
        val hooks = TestHooks()
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200, "real") }
        hooks.failNext("/v1/orders", FaultDecision(status = 200, body = """{"fake":true}"""))
        assertEquals("""{"fake":true}""", http.client.get("orders").bodyAsText())
        assertEquals(emptyList(), http.requests)
        hooks.failNext("/v1/orders", FaultDecision(status = 503, body = """{"message":"down"}"""))
        val error = assertFailsWith<HttpStatusError> { http.client.get("orders") }
        assertEquals(503, error.status)
        assertEquals("down", error.errorBody?.message)
        assertEquals(listOf(200, 503), hooks.exchanges.map { it.status })
    }

    @Test
    fun aDroppedConnectionIsNoConnectionAndIsRecorded() = runTest {
        val hooks = TestHooks()
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200) }
        hooks.failNext("/v1/orders", FaultDecision(drop = true))
        val error = assertFailsWith<Throwable> { http.client.get("orders") }
        assertTrue(isNoConnectionFailure(error), "$error")
        val exchange = hooks.exchanges.single()
        assertNull(exchange.status)
        assertSame(error, exchange.error)
        assertEquals(emptyList(), http.requests)
    }

    @Test
    fun aDelayWaitsThenSends() = runTest {
        val hooks = TestHooks()
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200, "ok") }
        hooks.failNext("/v1/orders", FaultDecision(delay = 1500.milliseconds))
        val before = currentTime
        assertEquals("ok", http.client.get("orders").bodyAsText())
        assertEquals(1500, currentTime - before)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun theHostOverrideSendsToAnotherBaseKeepingTheToken() = runTest {
        val hooks = TestHooks(host = "https://staging.local/v2")
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/v2/orders/7").respond(200, "staging") }
        assertEquals("staging", http.client.get("orders/7?full=1").bodyAsText())
        val sent = http.requests.single()
        assertEquals("staging.local", sent.url.host)
        assertEquals("Bearer token-1", sent.headers[HttpHeaders.Authorization])
        val exchange = hooks.exchanges.single()
        assertEquals("https://staging.local/v2/orders/7?full=1", exchange.url)
        assertEquals("https://test.local/v1/orders/7?full=1", exchange.requestedUrl)
    }

    @Test
    fun theHostOverrideLeavesOtherHostsAlone() = runTest {
        val hooks = TestHooks(host = "https://staging.local/v2")
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/cdn/a.png").respond(200) }
        http.client.get("https://cdn.local/cdn/a.png")
        assertEquals("cdn.local", http.requests.single().url.host)
    }

    @Test
    fun theHostOverrideMovesOnlyTheBaseHostUnderItsPath() = runTest {
        val hooks = TestHooks(host = "https://staging.local/v2")
        val http = mockHttp(config, hooks = hooks) {
            on(HttpMethod.Get, "/v1/a.png").respond(200)
            on(HttpMethod.Get, "/health").respond(200)
        }
        http.client.get("https://cdn.local/v1/a.png")                    // another host, the same path
        http.client.get("https://test.local/health")                     // the own host, outside the base path
        assertEquals(listOf("cdn.local", "test.local"), http.requests.map { it.url.host })
    }

    @Test
    fun expiringTheTokenSendsAnInvalidOneOnceAndRefreshes() = runTest {
        val backend = AuthBackend(this, serverToken = "token-2")      // only the refreshed token is accepted
        SinewHttp.debugExpireToken(backend.client)
        backend.client.get("orders")
        assertEquals(
            listOf("Bearer $EXPIRED_TOKEN", "Bearer token-2"),
            backend.requests.map { it.headers[HttpHeaders.Authorization] },
        )
        assertEquals(1, (backend.tokens as FakeTokenSource).refreshes)
        backend.client.get("orders")
        assertEquals("Bearer token-2", backend.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun expiringWaitsForARequestThatCarriesAToken() = runTest {
        val backend = AuthBackend(this, serverToken = "token-2")
        SinewHttp.debugExpireToken(backend.client)
        runCatching { backend.client.post("auth/login") }               // anonymous: no token to expire
        assertNull(backend.requests.single().headers[HttpHeaders.Authorization])
        backend.client.get("orders")
        assertEquals("Bearer $EXPIRED_TOKEN", backend.requests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun aFaultCannotBothDropAndAnswer() {
        assertFailsWith<IllegalArgumentException> { FaultDecision(status = 500, drop = true) }
        assertFailsWith<IllegalArgumentException> { FaultDecision(status = 99) }
    }
}
