package com.srctool.sinew.network

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun redirect(to: String, status: HttpStatusCode = HttpStatusCode.Found) =
    headersOf(HttpHeaders.Location, to) to status

class RedirectTest {
    /** Answers each path in [routes] with a redirect, and anything else with 200 and its URL. */
    private fun mock(routes: Map<String, Pair<io.ktor.http.Headers, HttpStatusCode>>) = Mock { request ->
        val route = routes[request.url.toString()]
        if (route != null) respond("", route.second, route.first) else respond(request.url.toString(), HttpStatusCode.OK)
    }

    @Test
    fun aSameHostRedirectKeepsAuthorization() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/old" to redirect("/v1/new")))
        val body = mock.client.get("old") { header(HttpHeaders.Authorization, "Bearer t") }.bodyAsText()
        assertEquals("https://api.example.com/v1/new", body)
        assertEquals(listOf("Bearer t", "Bearer t"), mock.requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun aCrossHostRedirectDropsAuthorizationAndCookies() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/file" to redirect("https://cdn.example.net/f.png?sig=1")))
        mock.client.get("file") {
            header(HttpHeaders.Authorization, "Bearer t")
            header(HttpHeaders.Cookie, "session=s")
            header("X-Build", "1")
        }
        val hop = mock.requests.last()
        assertEquals("https://cdn.example.net/f.png?sig=1", hop.url.toString())
        assertNull(hop.headers[HttpHeaders.Authorization])
        assertNull(hop.headers[HttpHeaders.Cookie])
        assertEquals("1", hop.headers["X-Build"])
    }

    @Test
    fun anotherPortIsAnotherHost() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/a" to redirect("https://api.example.com:8443/a")))
        mock.client.get("a") { header(HttpHeaders.Authorization, "Bearer t") }
        assertNull(mock.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun theOldQueryIsNotCarriedToTheNewLocation() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/old?page=2" to redirect("new")))
        assertEquals("https://api.example.com/v1/new", mock.client.get("old?page=2").bodyAsText())
    }

    @Test
    fun theLocationsOwnQueryIsKept() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/old?page=2" to redirect("new?page=3")))
        assertEquals("https://api.example.com/v1/new?page=3", mock.client.get("old?page=2").bodyAsText())
    }

    @Test
    fun everyRedirectStatusIsFollowed() = runTest {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val mock = mock(mapOf("https://api.example.com/v1/a" to redirect("b", HttpStatusCode.fromValue(status))))
            assertEquals("https://api.example.com/v1/b", mock.client.get("a").bodyAsText(), "status $status")
        }
    }

    @Test
    fun headIsFollowed() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/a" to redirect("b")))
        mock.client.head("a")
        assertEquals(2, mock.requests.size)
    }

    @Test
    fun aPostIsNotFollowed() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/a" to redirect("b", HttpStatusCode.SeeOther)))
        assertEquals(303, assertFailsWith<HttpStatusError> { mock.client.post("a") }.status)
        assertEquals(1, mock.requests.size)
    }

    @Test
    fun atMostFiveHopsAreFollowed() = runTest {
        val loop = Mock { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "again")) }
        assertEquals(302, assertFailsWith<HttpStatusError> { loop.client.get("start") }.status)
        assertEquals(1 + 5, loop.requests.size)
    }

    @Test
    fun fiveHopsAreEnough() = runTest {
        val routes = (0 until 5).associate { "https://api.example.com/v1/$it" to redirect("${it + 1}") }
        assertEquals("https://api.example.com/v1/5", mock(routes).client.get("0").bodyAsText())
    }

    @Test
    fun anHttpsToHttpRedirectIsNotFollowed() = runTest {
        val mock = mock(mapOf("https://api.example.com/v1/a" to redirect("http://api.example.com/v1/a")))
        assertEquals(302, assertFailsWith<HttpStatusError> { mock.client.get("a") }.status)
        assertEquals(1, mock.requests.size)
    }

    @Test
    fun aRedirectWithoutLocationIsReturnedAsIs() = runTest {
        val mock = Mock { respond("", HttpStatusCode.Found) }
        assertEquals(302, assertFailsWith<HttpStatusError> { mock.client.get("a") }.status)
    }
}

class SinewLogTest {
    private val secrets = listOf("s3cr3t-access", "r3fresh-token", "hunter2", "q-token", "session=abc")

    @Test
    fun eachExchangeIsOneRedactedLine() = runTest {
        val lines = mutableListOf<String>()
        val mock = Mock(config(logHttp = true), logger = { lines += it }) { request ->
            when {
                request.url.encodedPath.endsWith("/old") -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "auth/login"))
                else -> respond("""{"access":"s3cr3t-access","refresh":"r3fresh-token"}""", HttpStatusCode.OK, json)
            }
        }
        mock.client.post("auth/login?token=q-token") {
            header(HttpHeaders.Authorization, "Bearer s3cr3t-access")
            header(HttpHeaders.Cookie, "session=abc")
            contentType(ContentType.Application.Json)
            setBody("""{"user":"jane","password":"hunter2"}""")
        }.bodyAsText()
        mock.client.get("old") { header(HttpHeaders.Authorization, "Bearer s3cr3t-access") }

        assertEquals(3, lines.size)
        assertTrue(lines[0].matches(Regex("""POST api\.example\.com/v1/auth/login → 200 \(\d+ ms\)""")), lines[0])
        assertTrue(lines[1].startsWith("GET api.example.com/v1/old → 302 "), lines[1])
        assertTrue(lines[2].startsWith("GET api.example.com/v1/auth/login → 200 "), lines[2])
        for (line in lines) for (secret in secrets) assertFalse(secret in line, "\"$line\" contains $secret")
        assertFalse(lines.any { "Bearer" in it })
    }

    @Test
    fun aFailureIsLoggedByTypeOnly() = runTest {
        val lines = mutableListOf<String>()
        val mock = Mock(config(logHttp = true), logger = { lines += it }) { throw IOException("connect to 10.0.0.1 with token s3cr3t-access") }
        assertFailsWith<IOException> { mock.client.get("a") }
        assertTrue(lines.single().matches(Regex("""GET api\.example\.com/v1/a → failed: IOException \(\d+ ms\)""")), lines.single())
    }

    @Test
    fun nothingIsLoggedWhenLogHttpIsOff() = runTest {
        val lines = mutableListOf<String>()
        val mock = Mock(config(logHttp = false), logger = { lines += it }) { respondError(HttpStatusCode.InternalServerError) }
        assertFailsWith<HttpStatusError> { mock.client.get("a") }
        assertTrue(lines.isEmpty())
    }
}
