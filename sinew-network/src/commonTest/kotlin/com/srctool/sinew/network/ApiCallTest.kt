package com.srctool.sinew.network

import com.srctool.sinew.exception.ApiErrorException
import com.srctool.sinew.exception.ConflictException
import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.exception.ForbiddenException
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.NotFoundException
import com.srctool.sinew.exception.ParseException
import com.srctool.sinew.exception.RateLimitedException
import com.srctool.sinew.exception.RequestTimeOutException
import com.srctool.sinew.exception.ServerException
import com.srctool.sinew.exception.ServiceUnavailableException
import com.srctool.sinew.exception.UnauthorizedException
import com.srctool.sinew.exception.UndefinedErrorResponseException
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.models.Domain
import com.srctool.sinew.models.EnvelopeFailure
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.ObjectEnvelope
import com.srctool.sinew.models.Remote
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// An app-style envelope: `success`, `message` and `errors` beside `data`, mapped through the two hooks.

internal data class Order(val id: String, val status: OrderStatus) : Domain

internal enum class OrderStatus { Open, Shipped, Unknown }

@Serializable
internal enum class OrderStatusRemote {
    @SerialName("open") Open,
    @SerialName("shipped") Shipped,
    Unknown,
}

@Serializable
internal data class OrderRemote(val id: String, val status: OrderStatusRemote = OrderStatusRemote.Unknown) : Remote<Order>() {
    override fun toDomain() = Order(
        id,
        when (status) {
            OrderStatusRemote.Open -> OrderStatus.Open
            OrderStatusRemote.Shipped -> OrderStatus.Shipped
            OrderStatusRemote.Unknown -> OrderStatus.Unknown
        },
    )
}

@Serializable
internal data class OrderResponse(
    override val data: OrderRemote? = null,
    val success: Boolean = true,
    val message: String? = null,
    val errors: Map<String, List<String>>? = null,
) : ObjectEnvelope<OrderRemote, Order>() {
    override fun failure() = if (success) null else EnvelopeFailure(message, errors)
    override fun message() = message
}

private val order = Order("7", OrderStatus.Open)

class ApiCallTest {
    private val reported = mutableListOf<SinewException>()

    @BeforeTest
    fun installReporter() = CrashReporter.install { reported += it }

    @AfterTest
    fun resetReporter() = CrashReporter.install(CrashReporter.None)

    private fun backend(handler: MockRequestHandler) = Mock(handler = handler).client

    private suspend fun answer(status: HttpStatusCode, body: String = "", headers: io.ktor.http.Headers = json): Result<Order> {
        val client = backend { respond(body, status, headers) }
        return processApiCall("ORD", "GET") { client.get("orders/7").body<OrderResponse>() }
    }

    private suspend inline fun <reified E : SinewException> failsWith(status: Int, body: String = ""): E {
        val result = answer(HttpStatusCode.fromValue(status), body)
        val error = assertIs<E>(assertIs<Result.Failure>(result).error, "status $status")
        assertEquals(ExceptionLayer.Repository, error.layer)
        return error
    }

    // Success and the envelope hooks

    @Test
    fun aSuccessCarriesTheBackendsMessage() = runTest {
        val result = answer(HttpStatusCode.OK, """{"data":{"id":"7","status":"open"},"message":"Here you go."}""")
        assertEquals(Result.Success(order, "Here you go."), result)
    }

    @Test
    fun aSuccessWithoutAMessageHasAnEmptyOne() = runTest {
        assertEquals(Result.Success(order, ""), answer(HttpStatusCode.OK, """{"data":{"id":"7","status":"open"}}"""))
    }

    @Test
    fun anEnvelopeFailureWithFieldErrorsIsAValidationException() = runTest {
        val error = failsWith<ValidationException>(200, """{"success":false,"message":"Check the form.","errors":{"reason":["Required."]}}""")
        assertEquals(mapOf("reason" to listOf("Required.")), error.fieldErrors)
        assertEquals(ErrorMessage.Server("Check the form."), error.errorMessage)
        assertEquals("ORD-R-GET-VAL", error.code)
    }

    @Test
    fun anEnvelopeFailureWithoutFieldErrorsIsAnApiError() = runTest {
        val error = failsWith<ApiErrorException>(200, """{"success":false,"message":"Already shipped."}""")
        assertEquals(ErrorMessage.Server("Already shipped."), error.errorMessage)
    }

    @Test
    fun anEnvelopeFailureWithAnEmptyErrorsObjectIsAnApiError() = runTest {
        failsWith<ApiErrorException>(200, """{"success":false,"message":"No.","errors":{}}""")
    }

    @Test
    fun anEnvelopeFailureWithABlankMessageUsesTheLocalOne() = runTest {
        assertEquals("sinew.error.generic", (failsWith<ApiErrorException>(200, """{"success":false,"message":" "}""").errorMessage as ErrorMessage.Local).key)
        val validation = failsWith<ValidationException>(200, """{"success":false,"errors":{"a":["b"]}}""")
        assertEquals("sinew.error.validation", (validation.errorMessage as ErrorMessage.Local).key)
    }

    @Test
    fun anEnvelopeFailureIsCheckedBeforeToDomain() = runTest {
        failsWith<ApiErrorException>(200, """{"success":false,"message":"No."}""")   // no data: toDomain would throw
        assertTrue(reported.isEmpty())
    }

    // The handler table, one test per row

    @Test
    fun row2ATimeoutIsRequestTimeOut() = runTest {
        val client = backend { throw HttpRequestTimeoutException("https://api.example.com/v1/orders/7", 30_000) }
        val result = processApiCall("ORD", "GET") { client.get("orders/7").body<OrderResponse>() }
        assertEquals("ORD-R-GET-RTO", assertIs<RequestTimeOutException>((result as Result.Failure).error).code)
    }

    @Test
    fun row2AConnectTimeoutIsRequestTimeOut() = runTest {
        val client = backend { throw io.ktor.client.network.sockets.ConnectTimeoutException("connect timed out") }
        assertIs<RequestTimeOutException>((processApiCall("ORD", "GET") { client.get("a").body<OrderResponse>() } as Result.Failure).error)
    }

    @Test
    fun row2AnEngineWrappedTimeoutIsRequestTimeOut() = runTest {
        val wrapped = CancellationException("engine gave up", io.ktor.client.network.sockets.ConnectTimeoutException("timed out"))
        val result = processApiCall<Order>("ORD", "GET") { throw wrapped }
        assertIs<RequestTimeOutException>((result as Result.Failure).error)
    }

    @Test
    fun row4A400Or422WithFieldErrorsIsValidation() = runTest {
        for (status in listOf(400, 422)) {
            val error = failsWith<ValidationException>(status, """{"message":"Check the form.","errors":{"email":["Taken."]}}""")
            assertEquals(mapOf("email" to listOf("Taken.")), error.fieldErrors)
            assertEquals(ErrorMessage.Server("Check the form."), error.errorMessage)
        }
        val local = failsWith<ValidationException>(422, """{"errors":{"email":["Taken."]}}""")
        assertEquals("sinew.error.validation", (local.errorMessage as ErrorMessage.Local).key)
    }

    @Test
    fun row4A400WithoutFieldErrorsFallsToTheCatchAll() = runTest {
        assertEquals(ErrorMessage.Server("Bad input."), failsWith<ApiErrorException>(400, """{"message":"Bad input."}""").errorMessage)
        assertEquals(400, failsWith<UndefinedErrorResponseException>(400).status)
    }

    @Test
    fun row5A401IsUnauthorized() = runTest {
        assertEquals("ORD-R-GET-UNA", failsWith<UnauthorizedException>(401).code)
        assertEquals(ErrorMessage.Server("Wrong password."), failsWith<UnauthorizedException>(401, """{"message":"Wrong password."}""").errorMessage)
    }

    @Test
    fun row6Forbidden404AndConflict() = runTest {
        failsWith<ForbiddenException>(403)
        failsWith<NotFoundException>(404)
        assertEquals(ErrorMessage.Server("Taken."), failsWith<ConflictException>(409, """{"message":"Taken."}""").errorMessage)
    }

    @Test
    fun row7A429CarriesRetryAfterInSecondsOrAsADate() = runTest {
        val seconds = answer(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "30"))
        assertEquals(30.seconds, assertIs<RateLimitedException>((seconds as Result.Failure).error).retryAfter)
        val date = answer(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(Duration.ZERO, assertIs<RateLimitedException>((date as Result.Failure).error).retryAfter)   // a past date
        val none = failsWith<RateLimitedException>(429)
        assertNull(none.retryAfter)
    }

    @Test
    fun row8Every5xxExcept503And504IsAServerException() = runTest {
        for (status in listOf(500, 501, 502, 505, 507, 599)) assertEquals(status, failsWith<ServerException>(status).status)
        for (status in listOf(503, 504)) assertEquals(status, failsWith<ServiceUnavailableException>(status).status)
    }

    @Test
    fun row8AnHtml502PageHasTheLocalServerMessage() = runTest {
        val error = assertIs<ServerException>((answer(HttpStatusCode.BadGateway, "<html><h1>502</h1></html>", headersOf()) as Result.Failure).error)
        assertEquals("sinew.error.server", (error.errorMessage as ErrorMessage.Local).key)
    }

    @Test
    fun row9And10AnyOtherStatus() = runTest {
        assertEquals(ErrorMessage.Server("I'm a teapot."), failsWith<ApiErrorException>(418, """{"message":"I'm a teapot."}""").errorMessage)
        assertEquals(418, failsWith<UndefinedErrorResponseException>(418).status)
    }

    @Test
    fun a408StatusIsNotATimeout() = runTest {
        failsWith<ApiErrorException>(408, """{"message":"Took too long."}""")
        assertEquals(408, failsWith<UndefinedErrorResponseException>(408).status)
    }

    @Test
    fun row11JsonThatDoesntFitIsAReportedParseException() = runTest {
        assertIs<ParseException>((answer(HttpStatusCode.OK, """{"data":{"id":{"nested":true}}}""") as Result.Failure).error)
        assertIs<ParseException>((answer(HttpStatusCode.OK, """{"data":""") as Result.Failure).error)
        assertEquals(2, reported.count { it is ParseException })
    }

    @Test
    fun row12AMissingPayloadIsAReportedParseException() = runTest {
        val error = assertIs<ParseException>((answer(HttpStatusCode.OK, """{"data":null}""") as Result.Failure).error)
        assertEquals("ORD-R-GET-DF", error.code)
        assertSame(error, reported.single())
    }

    @Test
    fun anUnknownEnumValueIsUnknown() = runTest {
        val result = answer(HttpStatusCode.OK, """{"data":{"id":"7","status":"refunded"}}""")
        assertEquals(Order("7", OrderStatus.Unknown), (result as Result.Success).data)
    }

    @Test
    fun aBugInToDomainIsAReportedUnexpectedException() = runTest {
        val result = processApiCall<Order>("ORD", "GET") { error("a bug") }
        assertIs<UnexpectedException>((result as Result.Failure).error)
        assertEquals(1, reported.size)
    }

    @Test
    fun aSinewExceptionFromTheCallPassesThrough() = runTest {
        val offline = NoInternetConnectionException("AUTH", ExceptionLayer.Repository, "REFRESH")
        assertSame(offline, (processApiCall<Order>("ORD", "GET") { throw offline } as Result.Failure).error)
    }

    // The optional variant

    @Test
    fun optionalA404IsNull() = runTest {
        val client = backend { respond("""{"message":"Gone."}""", HttpStatusCode.NotFound, json) }
        assertEquals(Result.Success(null, ""), processOptionalApiCall("ORD", "FIND") { client.get("orders/7").body<OrderResponse>() })
    }

    @Test
    fun optionalAFoundItemAndOtherFailuresAreUnchanged() = runTest {
        val found = backend { respond("""{"data":{"id":"7","status":"open"},"message":"Ok."}""", HttpStatusCode.OK, json) }
        assertEquals(Result.Success(order, "Ok."), processOptionalApiCall("ORD", "FIND") { found.get("a").body<OrderResponse>() })
        val down = backend { respond("", HttpStatusCode.InternalServerError) }
        assertIs<ServerException>((processOptionalApiCall("ORD", "FIND") { down.get("a").body<OrderResponse>() } as Result.Failure).error)
    }

    // A plain Ktor client, without sinewErrorBody

    @Test
    fun ktorsOwnExpectSuccessErrorsAreClassifiedByStatus() = runTest {
        val plain = HttpClient(MockEngine) {
            expectSuccess = true
            install(ContentNegotiation) { json(SinewJson) }
            engine { addHandler { respond("""{"message":"Gone."}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.RetryAfter, "5")) } }
        }
        val error = assertIs<NotFoundException>((processApiCall("ORD", "GET") { plain.get("https://x.example/a").body<OrderResponse>() } as Result.Failure).error)
        assertEquals("sinew.error.notFound", (error.errorMessage as ErrorMessage.Local).key)   // the body isn't read here
    }
}

class RefreshResultTest {
    private fun failure(error: SinewException) = RefreshResult.from(Result.Failure(error))
    private val l = ExceptionLayer.Repository

    @Test
    fun tokensPassThrough() {
        val tokens = RefreshResult.Tokens("a", "r")
        assertSame(tokens, RefreshResult.from(Result.Success(tokens)))
    }

    @Test
    fun aRefusalIsRejected() {
        val refusals = listOf(
            UnauthorizedException("AUTH", l, "REFRESH"),
            ForbiddenException("AUTH", l, "REFRESH"),
            ValidationException("AUTH", l, "REFRESH"),
            ApiErrorException("AUTH", l, "REFRESH", ErrorMessage.Server("invalid_grant")),
            NotFoundException("AUTH", l, "REFRESH"),
            ConflictException("AUTH", l, "REFRESH"),
            UndefinedErrorResponseException("AUTH", l, "REFRESH", 400),
            UndefinedErrorResponseException("AUTH", l, "REFRESH", 410),
        )
        for (error in refusals) assertEquals(RefreshResult.Rejected, failure(error), error.code)
    }

    @Test
    fun anythingElseKeepsTheSession() {
        val kept = listOf(
            NoInternetConnectionException("AUTH", l, "REFRESH"),
            RequestTimeOutException("AUTH", l, "REFRESH"),
            ServerException("AUTH", l, "REFRESH", 500),
            ServiceUnavailableException("AUTH", l, "REFRESH", 503),
            RateLimitedException("AUTH", l, "REFRESH", null),
            UndefinedErrorResponseException("AUTH", l, "REFRESH", 408),
            ParseException("AUTH", l, "REFRESH"),
            UnexpectedException("AUTH", l, "REFRESH"),
        )
        for (error in kept) assertEquals(RefreshResult.Unavailable(error), failure(error), error.code)
    }
}
