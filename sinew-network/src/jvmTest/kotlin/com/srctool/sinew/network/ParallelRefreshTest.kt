package com.srctool.sinew.network

import com.srctool.sinew.testing.FakeTokenSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ParallelRefreshTest {
    /**
     * Real threads, real interleavings: fifty requests with an expired token still cause exactly one refresh. The
     * refresh finishes at once, so 401s keep arriving just as it completes: the window where a request that read the
     * old token must not start a second refresh. CI caught that race once; this test reproduces it without the fix.
     */
    @Test
    fun fiftyParallel401sOnRealThreadsCauseOneRefresh() = runBlocking {
        repeat(200) { round ->
            val tokens = FakeTokenSource("token-1").apply {
                onRefresh = { rotate(); RefreshOutcome.Refreshed }
            }
            val client = SinewHttp.client(config(), tokens, MockEngine) {
                engine {
                    addHandler { request ->
                        delay(1)
                        if (request.headers[HttpHeaders.Authorization] == "Bearer token-2") respond("ok", HttpStatusCode.OK)
                        else respond("", HttpStatusCode.Unauthorized)
                    }
                }
            }
            val bodies = withContext(Dispatchers.Default) {
                (0 until 50).map { async { client.get("orders/$it").bodyAsText() } }.awaitAll()
            }
            assertEquals(List(50) { "ok" }, bodies, "round $round")
            assertEquals(1, tokens.refreshes, "round $round")
            client.close()
        }
    }
}
