@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.testing

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.RequestTimeOutException
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.network.apiHandlers
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The network scenarios, through mockHttp and the network guard's real handlers. */
class MockHttpTest {
    private suspend fun MockHttp.guardedGet(path: String): Result<String> =
        processCall("ORD", "GET", ExceptionLayer.Repository, apiHandlers("ORD", "GET")) { client.get(path).bodyAsText() }

    @Test
    fun aRouteAnswersWithItsFixtureAndTheRequestIsRecorded() = runTest {
        val http = mockHttp { on(HttpMethod.Get, "/v1/orders").respond(200, fixture("orders_page.json")) }
        assertEquals(fixture("orders_page.json"), http.client.get("orders").bodyAsText())
        val request = http.requests.single()
        assertEquals("/v1/orders", request.url.encodedPath)
        assertEquals("Bearer token-1", request.headers[HttpHeaders.Authorization])
        assertEquals("en", request.headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun a401IsRefreshedOnceAndReplayedWithTheNewToken() = runTest {
        val tokens = FakeTokenSource()
        val http = mockHttp(tokenSource = tokens) {
            on(HttpMethod.Get, "/v1/orders").respond(401).respond(200, """{"data":[]}""")
        }
        assertEquals("""{"data":[]}""", http.client.get("orders").bodyAsText())
        assertEquals(1, tokens.refreshes)
        assertEquals(
            listOf("Bearer token-1", "Bearer token-2"),
            http.requests(HttpMethod.Get, "/v1/orders").map { it.headers[HttpHeaders.Authorization] },
        )
    }

    @Test
    fun aDroppedConnectionIsNoConnectionAndUnexpectedInABrowser() = runTest {
        val http = mockHttp { on(HttpMethod.Get, "/v1/orders").drop() }
        val error = assertIs<Result.Failure>(http.guardedGet("orders")).error
        if (inBrowser) assertIs<UnexpectedException>(error) else assertIs<NoInternetConnectionException>(error)
    }

    @Test
    fun aDelayedResponseTakesVirtualTime() = runTest {
        val http = mockHttp { on(HttpMethod.Get, "/v1/orders").respond(200, "{}", delay = 5.seconds) }
        assertEquals(Result.Success("{}"), http.guardedGet("orders"))
        assertEquals(5_000, currentTime)
    }

    @Test
    fun aResponseLaterThanTheReadDeadlineTimesOut() = runTest {
        val http = mockHttp { on(HttpMethod.Get, "/v1/orders").respond(200, "{}", delay = 45.seconds) }
        val error: SinewException = assertIs<Result.Failure>(http.guardedGet("orders")).error
        assertIs<RequestTimeOutException>(error)
        assertEquals(30_000, currentTime, "the default read deadline")
    }

    @Test
    fun aRouteRepeatsItsLastAnswerAndHeadersAreSent() = runTest {
        val http = mockHttp {
            on(HttpMethod.Post, "/v1/orders").respond(201, "{}", headers = mapOf("Location" to "/v1/orders/9"))
        }
        repeat(2) {
            val response = http.client.post("orders") { setBody("{}") }
            assertEquals(201, response.status.value)
            assertEquals("/v1/orders/9", response.headers["Location"])
            assertEquals("application/json", response.headers[HttpHeaders.ContentType])
        }
        assertEquals(2, http.requests(HttpMethod.Post, "/v1/orders").size)
    }

    @Test
    fun requestsAreFilteredByMethodAndPath() = runTest {
        val http = mockHttp {
            on(HttpMethod.Get, "/v1/orders").respond(200, "[]")
            on(HttpMethod.Post, "/v1/orders").respond(201, "{}")
        }
        http.client.get("orders")
        http.client.post("orders") { setBody("{}") }
        http.client.post("orders") { setBody("{}") }
        assertEquals(1, http.requests(HttpMethod.Get, "/v1/orders").size)
        assertEquals(2, http.requests(HttpMethod.Post, "/v1/orders").size)
        assertEquals(3, http.requests.size)
    }

    @Test
    fun aRequestNoRouteMatchesFailsTheTestWithItsName() = runTest {
        val http = mockHttp { on(HttpMethod.Get, "/v1/orders").respond(200) }
        val error = assertFailsWith<AssertionError> { http.client.get("customers") }
        assertTrue("GET /v1/customers" in error.message.orEmpty(), error.message)
    }

    @Test
    fun withRecordingReporterRecordsAndRestoresThePreviousReporter() = runTest {
        val outer = RecordingCrashReporter()
        com.srctool.sinew.exception.CrashReporter.install(outer)
        try {
            val http = mockHttp { on(HttpMethod.Get, "/v1/orders").respond(200, "not json", delay = 0.seconds) }
            withRecordingReporter { reporter ->
                val bug = IllegalStateException("bug")
                processCall<Unit>("ORD", "GET", ExceptionLayer.Repository, emptyList()) { throw bug }
                assertEquals(listOf("ORD-R-GET-GEN"), reporter.codes)
            }
            assertTrue(com.srctool.sinew.exception.CrashReporter.current === outer)
            http.client.get("orders")
        } finally {
            com.srctool.sinew.exception.CrashReporter.install(com.srctool.sinew.exception.CrashReporter.None)
        }
    }

    @Test
    fun theReporterIsRestoredEvenWhenTheBlockFails() {
        val before = com.srctool.sinew.exception.CrashReporter.current
        assertFailsWith<IllegalStateException> {
            withRecordingReporter { throw IllegalStateException("the test failed") }
        }
        assertTrue(com.srctool.sinew.exception.CrashReporter.current === before)
    }
}

