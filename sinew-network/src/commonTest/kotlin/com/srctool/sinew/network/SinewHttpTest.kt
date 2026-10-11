package com.srctool.sinew.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val BASE = "https://api.example.com/v1"

internal fun config(
    baseUrl: String = BASE,
    locale: () -> String = { "en" },
    defaultHeaders: () -> Map<String, String> = { emptyMap() },
    logHttp: Boolean = false,
    errorBodyReader: ErrorBodyReader = ErrorBodyReader.Default,
) = SinewConfig(
    baseUrl = baseUrl,
    locale = locale,
    defaultHeaders = defaultHeaders,
    connectTimeout = 5.seconds,
    readTimeout = 10.seconds,
    writeTimeout = 20.seconds,
    logHttp = logHttp,
    errorBodyReader = errorBodyReader,
)

/** A Sinew client on a MockEngine that records every request it receives. */
internal class Mock(
    config: SinewConfig = config(),
    logger: HttpLogger = HttpLogger { },
    handler: MockRequestHandler = { respond("{}", HttpStatusCode.OK, json) },
) {
    val requests = mutableListOf<HttpRequestData>()
    val client: HttpClient = SinewHttp.client(config, MockEngine, logger) {
        engine {
            addHandler { request ->
                requests += request
                handler(request)
            }
        }
    }
}

internal val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

class SinewHttpTest {
    @Test
    fun aRelativePathResolvesUnderTheBasePath() = runTest {
        val mock = Mock()
        mock.client.get("orders/42")
        assertEquals("https://api.example.com/v1/orders/42", mock.requests.single().url.toString())
    }

    @Test
    fun aBaseUrlWithATrailingSlashResolvesTheSame() = runTest {
        val mock = Mock(config(baseUrl = "$BASE/"))
        mock.client.get("orders")
        assertEquals("https://api.example.com/v1/orders", mock.requests.single().url.toString())
    }

    // Timeouts

    private fun HttpRequestData.timeouts() = getCapabilityOrNull(HttpTimeoutCapability)!!.let {
        Triple(it.connectTimeoutMillis, it.requestTimeoutMillis, it.socketTimeoutMillis)
    }

    @Test
    fun readsUseTheReadTimeoutAndWritesTheWriteTimeout() = runTest {
        val mock = Mock()
        mock.client.get("a")
        mock.client.head("a")
        mock.client.post("a")
        mock.client.put("a")
        mock.client.delete("a")
        val read = Triple(5_000L, 10_000L, 10_000L)
        val write = Triple(5_000L, 20_000L, 20_000L)
        assertEquals(listOf(read, read, write, write, write), mock.requests.map { it.timeouts() })
    }

    @Test
    fun aRequestsOwnTimeoutIsKept() = runTest {
        val mock = Mock()
        mock.client.post("uploads") { timeout { requestTimeoutMillis = 300_000 } }
        assertEquals(Triple(null, 300_000L, null), mock.requests.single().timeouts())
    }

    // Headers

    @Test
    fun acceptAndAcceptLanguageAreSent() = runTest {
        val mock = Mock()
        mock.client.get("a")
        val headers = mock.requests.single().headers
        assertEquals("application/json", headers[HttpHeaders.Accept])
        assertEquals("en", headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun acceptLanguageFollowsALocaleChangeBetweenTwoRequests() = runTest {
        var language = "en"
        val mock = Mock(config(locale = { language }))
        mock.client.get("a")
        language = "id"
        mock.client.get("a")
        assertEquals(listOf("en", "id"), mock.requests.map { it.headers[HttpHeaders.AcceptLanguage] })
    }

    @Test
    fun aBlankLocaleSendsNoAcceptLanguage() = runTest {
        val mock = Mock(config(locale = { "" }))
        mock.client.get("a")
        assertNull(mock.requests.single().headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun defaultHeadersAreReadPerRequest() = runTest {
        var build = 1
        val mock = Mock(config(defaultHeaders = { mapOf("X-Build" to "$build") }))
        mock.client.get("a")
        build = 2
        mock.client.get("a")
        assertEquals(listOf("1", "2"), mock.requests.map { it.headers["X-Build"] })
    }

    @Test
    fun aHeaderTheRequestSetsWins() = runTest {
        val mock = Mock(config(defaultHeaders = { mapOf("X-Build" to "1") }))
        mock.client.get("a") {
            header(HttpHeaders.AcceptLanguage, "fr")
            header(HttpHeaders.Accept, "text/csv")
            header("X-Build", "9")
        }
        val headers = mock.requests.single().headers
        assertEquals(listOf("fr"), headers.getAll(HttpHeaders.AcceptLanguage))
        assertEquals(listOf("text/csv"), headers.getAll(HttpHeaders.Accept))
        assertEquals(listOf("9"), headers.getAll("X-Build"))
    }

    // The validator

    @Test
    fun aSuccessIsReturned() = runTest {
        val mock = Mock { respond("""{"id":1}""", HttpStatusCode.Created, json) }
        assertEquals("""{"id":1}""", mock.client.post("a").bodyAsText())
    }

    @Test
    fun aFailedStatusThrowsWithTheReadErrorBody() = runTest {
        val mock = Mock { respond("""{"message":"Check the form.","errors":{"email":["Taken."]}}""", HttpStatusCode.UnprocessableEntity, json) }
        val error = assertFailsWith<HttpStatusError> { mock.client.post("users") }
        assertEquals(422, error.status)
        assertEquals(ErrorBody("Check the form.", mapOf("email" to listOf("Taken."))), error.errorBody)
        assertNull(error.retryAfter)
    }

    @Test
    fun anHtmlErrorPageHasNoErrorBody() = runTest {
        val mock = Mock { respond("<html>502 Bad Gateway</html>", HttpStatusCode.BadGateway) }
        val error = assertFailsWith<HttpStatusError> { mock.client.get("a") }
        assertEquals(502, error.status)
        assertNull(error.errorBody)
    }

    @Test
    fun retryAfterIsRead() = runTest {
        val mock = Mock { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) }
        assertEquals(30.seconds, assertFailsWith<HttpStatusError> { mock.client.get("a") }.retryAfter)
    }

    @Test
    fun theConfigsOwnReaderIsUsed() = runTest {
        val reader = ErrorBodyReader { status, body -> ErrorBody("$status/$body", null) }
        val mock = Mock(config(errorBodyReader = reader)) { respond("nope", HttpStatusCode.Conflict) }
        assertEquals(ErrorBody("409/nope", null), assertFailsWith<HttpStatusError> { mock.client.put("a") }.errorBody)
    }

    @Test
    fun theValidatorSeesTheResponseAfterRedirects() = runTest {
        val mock = Mock { request ->
            if (request.url.encodedPath.endsWith("/old")) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "new"))
            else respond("ok", HttpStatusCode.OK)
        }
        assertEquals("ok", mock.client.get("old").bodyAsText())
        assertEquals(2, mock.requests.size)
    }

    // SinewJson

    @Serializable
    private enum class Status { Open, Shipped, Unknown }

    @Serializable
    private data class OrderDto(val id: Int, val note: String? = null, val status: Status = Status.Unknown)

    @Test
    fun sinewJsonIgnoresUnknownKeysAndCoercesUnknownEnums() {
        assertEquals(
            OrderDto(7, status = Status.Unknown),
            SinewJson.decodeFromString<OrderDto>("""{"id":7,"status":"Refunded","extra":{"a":1}}"""),
        )
        assertEquals(OrderDto(7, status = Status.Shipped), SinewJson.decodeFromString<OrderDto>("""{"id":7,"status":"Shipped"}"""))
        assertTrue(SinewJson.encodeToString(OrderDto.serializer(), OrderDto(1)).contains("\"note\"").not())
    }

    @Test
    fun theClientDecodesWithSinewJson() = runTest {
        val mock = Mock { respond("""{"id":7,"status":"Refunded","extra":true}""", HttpStatusCode.OK, json) }
        assertEquals(OrderDto(7), mock.client.get("orders/7").body<OrderDto>())
    }
}
