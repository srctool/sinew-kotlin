package com.srctool.sinew.testing

import com.srctool.sinew.network.HttpLogger
import com.srctool.sinew.network.SinewConfig
import com.srctool.sinew.network.SinewHttp
import com.srctool.sinew.network.TokenSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

/**
 * A Sinew client ([SinewHttp.client], with the auth, header, timeout and error layers) on Ktor's mock engine, which
 * answers by method and path and records every request. The engine runs on the test's scheduler, so a response's
 * delay is virtual time, and the client's deadlines fire as they would.
 *
 * ```kotlin
 * @Test fun ordersLoad() = runTest {
 *     val http = mockHttp {
 *         on(HttpMethod.Get, "/v1/orders").respond(200, fixture("orders_page.json"))
 *     }
 *     val result = OrderRepository(OrderApi(http.client)).page(1)
 *     assertEquals("/v1/orders", http.requests.single().url.encodedPath)
 * }
 * ```
 *
 * A route answers with its responses in order, repeating the last one. A request no route matches fails the test
 * with an `AssertionError` naming it.
 */
public fun TestScope.mockHttp(
    config: SinewConfig = SinewConfig(baseUrl = "https://test.local/v1/", locale = { "en" }),
    tokenSource: TokenSource = FakeTokenSource(),
    logger: HttpLogger = HttpLogger { },
    routes: MockRoutes.() -> Unit,
): MockHttp {
    val table = MockRoutes().apply(routes)
    val recorded = mutableListOf<HttpRequestData>()
    val dispatcher = StandardTestDispatcher(testScheduler)
    // The engine and refreshes on the test's scheduler: a refresh runs in virtual time with the requests it serves.
    val client = SinewHttp.client(config, tokenSource, MockEngine, logger, dispatcher) {
        engine {
            this.dispatcher = dispatcher
            addHandler { request ->
                recorded += request
                when (val answer = table.answer(request.method, request.url.encodedPath)) {
                    null -> throw AssertionError("No mock route for ${request.method.value} ${request.url.encodedPath}")
                    is MockAnswer.Drop -> throw droppedConnection()
                    is MockAnswer.Respond -> {
                        if (answer.delay.isPositive()) delay(answer.delay)
                        val headers = buildMap {
                            if (answer.body.isNotEmpty()) put(HttpHeaders.ContentType, listOf("application/json"))
                            answer.headers.forEach { (name, value) -> put(name, listOf(value)) }
                        }
                        respond(answer.body, HttpStatusCode.fromValue(answer.status), headersOf(*headers.toList().toTypedArray()))
                    }
                }
            }
        }
    }
    return MockHttp(client, recorded)
}

/** A [mockHttp] client and what it was asked. */
public class MockHttp internal constructor(
    /** The Sinew client: give it to the API class under test. */
    public val client: HttpClient,
    private val recorded: List<HttpRequestData>,
) {
    /** Every request the engine received, replays included, in order. */
    public val requests: List<HttpRequestData> get() = recorded.toList()

    /** The requests for [method] and [path]. */
    public fun requests(method: HttpMethod, path: String): List<HttpRequestData> =
        recorded.filter { it.method == method && it.url.encodedPath == path }
}

/** The routes of a [mockHttp] client: `on(HttpMethod.Get, "/v1/orders").respond(200, body)`. */
public class MockRoutes internal constructor() {
    private val routes = mutableMapOf<Pair<HttpMethod, String>, Route>()

    /** The route for [method] and the request's whole [path] (`/v1/orders`, without the query). */
    public fun on(method: HttpMethod, path: String): Route = routes.getOrPut(method to path) { Route() }

    internal fun answer(method: HttpMethod, path: String): MockAnswer? = routes[method to path]?.next()

    /** One route's answers, given in order; the last one repeats. */
    public class Route internal constructor() {
        private val answers = mutableListOf<MockAnswer>()
        private var given = 0

        /** Answers with [status], [body] (JSON) and [headers], after [delay] of virtual time. */
        public fun respond(
            status: Int = 200,
            body: String = "",
            headers: Map<String, String> = emptyMap(),
            delay: Duration = Duration.ZERO,
        ): Route = apply { answers += MockAnswer.Respond(status, body, headers, delay) }

        /** Drops the connection, with the error the platform's engine throws then. */
        public fun drop(): Route = apply { answers += MockAnswer.Drop }

        internal fun next(): MockAnswer? = answers.getOrNull(minOf(given++, answers.lastIndex))
    }
}

internal sealed interface MockAnswer {
    class Respond(val status: Int, val body: String, val headers: Map<String, String>, val delay: Duration) : MockAnswer
    data object Drop : MockAnswer
}

/**
 * What the platform's engine throws when the connection drops: a `SocketException` (OkHttp), Darwin's
 * `NSURLErrorNetworkConnectionLost`, and on the web the `TypeError` every failed `fetch` is, which Sinew counts as no
 * connection only while the browser is offline.
 */
internal expect fun droppedConnection(): Throwable
