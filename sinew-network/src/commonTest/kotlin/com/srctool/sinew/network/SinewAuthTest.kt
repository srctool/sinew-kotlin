package com.srctool.sinew.network

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.testing.FakeTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A backend that accepts only [serverToken], on the test dispatcher, so every interleaving is written in order.
 * `/v1/auth/login` is anonymous; `hold/...` paths wait for [hold] before answering.
 */
internal class AuthBackend(
    scope: TestScope,
    val tokens: TokenSource = FakeTokenSource("token-1"),
    var serverToken: String? = "token-1",
) {
    val requests = mutableListOf<HttpRequestData>()
    val hold = CompletableDeferred<Unit>()
    var failReplaysOf: String? = null

    private val dispatcher = StandardTestDispatcher(scope.testScheduler)

    val client: HttpClient = SinewHttp.client(
        config(anonymousPaths = setOf("/auth/login")),
        tokens,
        MockEngine,
        HttpLogger { },
        dispatcher,
    ) {
        engine {
            dispatcher = this@AuthBackend.dispatcher
            addHandler { request ->
                requests += request
                val path = request.url.encodedPath
                if (path.contains("/hold/")) hold.await()
                val authorized = request.headers[HttpHeaders.Authorization] == "Bearer $serverToken"
                when {
                    request.url.host != "api.example.com" || request.url.port != 443 ->
                        respond("", HttpStatusCode.Unauthorized)
                    path == "/v1/auth/login" -> respond("anonymous", HttpStatusCode.Unauthorized)
                    authorized && path == failReplaysOf -> respond("", HttpStatusCode.InternalServerError)
                    authorized -> respond(request.headers[HttpHeaders.Authorization]!!, HttpStatusCode.OK)
                    else -> respond("", HttpStatusCode.Unauthorized)
                }
            }
        }
    }

    fun sentTokens(path: String) = requests.filter { it.url.encodedPath == path }.map { it.headers[HttpHeaders.Authorization] }
}

private val offline = NoInternetConnectionException("AUTH", ExceptionLayer.Repository, "REFRESH")

class SinewAuthTest {
    @Test
    fun aValidTokenIsAttached() = runTest {
        val backend = AuthBackend(this)
        assertEquals("Bearer token-1", backend.client.get("orders").bodyAsText())
    }

    @Test
    fun tenParallel401sCauseOneRefreshAndAllReplayOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tokens = FakeTokenSource("token-1").apply { onRefresh = { gate.await(); rotate(); RefreshOutcome.Refreshed } }
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val calls = (0 until 10).map { async { backend.client.get("orders/$it").bodyAsText() } }
        runCurrent()
        assertEquals(10, backend.requests.size)                                    // all ten got 401 and wait
        assertEquals(1, tokens.refreshes)

        gate.complete(Unit)
        assertEquals(List(10) { "Bearer token-2" }, calls.awaitAll())
        assertEquals(1, tokens.refreshes)
        for (i in 0 until 10) assertEquals(listOf("Bearer token-1", "Bearer token-2"), backend.sentTokens("/v1/orders/$i"))
    }

    @Test
    fun rejectedClearsOnceAndFailsEveryWaiterWith401() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tokens = FakeTokenSource("token-1").apply { onRefresh = { gate.await(); RefreshOutcome.Rejected } }
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val calls = (0 until 10).map { async { runCatching { backend.client.get("orders/$it") } } }
        runCurrent()
        gate.complete(Unit)
        for (result in calls.awaitAll()) assertEquals(401, (result.exceptionOrNull() as HttpStatusError).status)
        assertEquals(1, tokens.refreshes)
        assertEquals(1, tokens.clears)
        assertNull(tokens.accessToken())
        assertEquals(10, backend.requests.size)                                    // nothing replayed
    }

    @Test
    fun unavailableFailsEveryWaiterWithItsCauseAndKeepsTheSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tokens = FakeTokenSource("token-1").apply { onRefresh = { gate.await(); RefreshOutcome.Unavailable(offline) } }
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val calls = (0 until 10).map { async { runCatching { backend.client.get("orders/$it") } } }
        runCurrent()
        gate.complete(Unit)
        for (result in calls.awaitAll()) assertSame(offline, result.exceptionOrNull())
        assertEquals(0, tokens.clears)
        assertEquals("token-1", tokens.accessToken())
    }

    @Test
    fun aReplayed401FailsWithoutASecondRefresh() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens, serverToken = "never")
        assertEquals(401, assertFailsWith<HttpStatusError> { backend.client.get("orders") }.status)
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), backend.sentTokens("/v1/orders"))
    }

    @Test
    fun aReplayReturning500FailsItsCallerAndTheNextRequestWorks() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens, serverToken = "token-2").apply { failReplaysOf = "/v1/flaky" }
        assertEquals(500, assertFailsWith<HttpStatusError> { backend.client.get("flaky") }.status)
        assertEquals("Bearer token-2", backend.client.get("orders").bodyAsText())
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun a401ForARequestSentWithAnOlderTokenReplaysWithoutRefreshing() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val slow = async { backend.client.get("hold/slow").bodyAsText() }        // sent with token-1, answered later
        runCurrent()
        assertEquals("Bearer token-2", backend.client.get("orders").bodyAsText()) // 401, refresh, replay
        backend.hold.complete(Unit)
        assertEquals("Bearer token-2", slow.await())                               // its 401 replays at once
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun theFirstCallerCancelledMidRefreshLeavesTheOthersTheOutcome() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tokens = FakeTokenSource("token-1").apply { onRefresh = { gate.await(); rotate(); RefreshOutcome.Refreshed } }
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val first = async { backend.client.get("orders/0").bodyAsText() }
        runCurrent()                                                               // it started the refresh
        val others = (1 until 10).map { async { backend.client.get("orders/$it").bodyAsText() } }
        runCurrent()
        first.cancel()
        gate.complete(Unit)
        assertEquals(List(9) { "Bearer token-2" }, others.awaitAll())
        assertTrue(first.isCancelled)
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun aRefreshThatThrowsFailsEveryWaiterWithTheBug() = runTest {
        val bug = IllegalStateException("a bug in the token source")
        val tokens = FakeTokenSource("token-1").apply { onRefresh = { throw bug } }
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val calls = (0 until 3).map { async { runCatching { backend.client.get("orders/$it") } } }
        for (result in calls.awaitAll()) assertEquals(bug.message, result.exceptionOrNull()?.message)
        assertEquals("token-1", tokens.accessToken())
    }

    @Test
    fun aRequestWithoutATokenRefreshesAndReplays() = runTest {
        val tokens = FakeTokenSource(accessToken = null)                          // cold start: only a refresh token
        val backend = AuthBackend(this, tokens, serverToken = "token-1")
        assertEquals("Bearer token-1", backend.client.get("orders").bodyAsText())
        assertEquals(listOf(null, "Bearer token-1"), backend.sentTokens("/v1/orders"))
    }

    @Test
    fun anonymousPathsUnderTheBasePathNeverGetATokenOrARefresh() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens)
        assertEquals(401, assertFailsWith<HttpStatusError> { backend.client.get("auth/login") }.status)
        assertEquals(listOf(null), backend.sentTokens("/v1/auth/login"))
        assertEquals(0, tokens.refreshes)
    }

    @Test
    fun aPathThatOnlyStartsLikeAnAnonymousOneGetsAToken() = runTest {
        val backend = AuthBackend(this, serverToken = "token-1")
        backend.client.get("auth/login/history")
        assertEquals(listOf("Bearer token-1"), backend.sentTokens("/v1/auth/login/history"))
    }

    @Test
    fun anotherHostOrPortNeverGetsATokenOrARefresh() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens)
        assertFailsWith<HttpStatusError> { backend.client.get("https://cdn.example.net/v1/a.png") }
        assertFailsWith<HttpStatusError> { backend.client.get("https://api.example.com:8443/v1/orders") }
        assertTrue(backend.requests.all { it.headers[HttpHeaders.Authorization] == null })
        assertEquals(0, tokens.refreshes)
    }

    @Test
    fun aRequestsOwnAuthorizationIsLeftAlone() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens)
        assertFailsWith<HttpStatusError> { backend.client.get("orders") { header(HttpHeaders.Authorization, "Basic dXNlcg==") } }
        assertEquals(listOf("Basic dXNlcg=="), backend.sentTokens("/v1/orders"))
        assertEquals(0, tokens.refreshes)
    }

    @Test
    fun aOneShotBodyRefreshesButIsNotReplayed() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        assertEquals(401, assertFailsWith<HttpStatusError> { backend.client.post("uploads") { setBody(ByteReadChannel("file")) } }.status)
        assertEquals(1, tokens.refreshes)
        assertEquals(1, backend.requests.size)
        assertEquals("Bearer token-2", backend.client.post("uploads") { setBody(ByteReadChannel("file")) }.bodyAsText())
    }

    @Test
    fun signOutMidRefreshStoresNothingAndFailsTheWaiters() = runTest {
        val store = MemoryStore()
        val answer = CompletableDeferred<RefreshResult>()
        val ended = mutableListOf<Unit>()
        val tokens = SessionTokenSource(store, { answer.await() }, onSessionEnded = { ended += Unit })
        tokens.start("a-1", "r-1")
        val backend = AuthBackend(this, tokens, serverToken = "a-2")
        val calls = (0 until 3).map { async { runCatching { backend.client.get("orders/$it") } } }
        runCurrent()                                                               // the refresh waits on the backend

        SinewHttp.resetAuth(backend.client)
        tokens.clear()
        for (result in calls.awaitAll()) assertEquals(401, (result.exceptionOrNull() as HttpStatusError).status)
        answer.complete(RefreshResult.Tokens("a-2", "r-2"))                       // too late: the refresh was stopped
        runCurrent()
        assertNull(tokens.accessToken())
        assertNull(store.token)
        assertTrue(ended.isNotEmpty())
    }

    @Test
    fun signOutMidRefreshWithoutResetAuthStillStoresNothing() = runTest {
        val store = MemoryStore()
        val answer = CompletableDeferred<RefreshResult>()
        val tokens = SessionTokenSource(store, { answer.await() })
        tokens.start("a-1", "r-1")
        val backend = AuthBackend(this, tokens, serverToken = "a-2")
        val calls = (0 until 3).map { async { runCatching { backend.client.get("orders/$it") } } }
        runCurrent()

        tokens.clear()
        answer.complete(RefreshResult.Tokens("a-2", "r-2"))                       // the epoch moved: Rejected
        for (result in calls.awaitAll()) assertEquals(401, (result.exceptionOrNull() as HttpStatusError).status)
        assertNull(tokens.accessToken())
        assertNull(store.token)
    }

    @Test
    fun a401ArrivingAfterSignOutDoesNotRefresh() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val slow = async { runCatching { backend.client.get("hold/slow") } }      // sent with token-1
        runCurrent()
        tokens.clear()                                                             // the user signs out
        backend.hold.complete(Unit)
        assertEquals(401, (slow.await().exceptionOrNull() as HttpStatusError).status)
        assertEquals(0, tokens.refreshes)
    }

    @Test
    fun aReplayRedirectedBackToA401DoesNotRefreshAgain() = runTest {
        val tokens = FakeTokenSource("token-1")
        val requests = mutableListOf<String?>()
        val client = SinewHttp.client(config(), tokens, MockEngine, HttpLogger { }, StandardTestDispatcher(testScheduler)) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val auth = request.headers[HttpHeaders.Authorization]
                    requests += "${request.url.encodedPath} $auth"
                    when {
                        auth == "Bearer token-1" -> respond("", HttpStatusCode.Unauthorized)               // expired
                        request.url.encodedPath == "/v1/old" -> respond("", HttpStatusCode.Found, io.ktor.http.headersOf(HttpHeaders.Location, "new"))
                        else -> respond("", HttpStatusCode.Unauthorized)                                    // the new path refuses even token-2
                    }
                }
            }
        }
        assertEquals(401, assertFailsWith<HttpStatusError> { client.get("old") }.status)
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf<String?>("/v1/old Bearer token-1", "/v1/old Bearer token-2", "/v1/new Bearer token-2"), requests)
    }

    @Test
    fun resetAuthStopsTheRunningRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tokens = FakeTokenSource("token-1").apply { onRefresh = { gate.await(); rotate(); RefreshOutcome.Refreshed } }
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        val call = async { runCatching { backend.client.get("orders") } }
        runCurrent()
        SinewHttp.resetAuth(backend.client)
        assertEquals(401, (call.await().exceptionOrNull() as HttpStatusError).status)
        gate.complete(Unit)
        runCurrent()
        assertEquals("token-1", tokens.accessToken())                              // the stopped refresh never rotated it
    }

    @Test
    fun afterResetAuthTheNextRequestRefreshesAfresh() = runTest {
        val tokens = FakeTokenSource("token-1")
        val backend = AuthBackend(this, tokens, serverToken = "token-2")
        SinewHttp.resetAuth(backend.client)                                        // nothing running: harmless
        assertEquals("Bearer token-2", backend.client.get("orders").bodyAsText())
        assertEquals(1, tokens.refreshes)
    }
}

internal class MemoryStore(var token: String? = null) : RefreshTokenStore {
    override suspend fun read(): String? = token
    override suspend fun write(token: String) {
        this.token = token
    }
    override suspend fun delete() {
        token = null
    }
}
