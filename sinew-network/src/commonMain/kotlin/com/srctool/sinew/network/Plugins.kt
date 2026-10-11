package com.srctool.sinew.network

import io.ktor.client.plugins.HttpCallValidatorConfig
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.exclude
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSecure
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.KtorDsl
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val ReadMethods = setOf(HttpMethod.Get, HttpMethod.Head)

/** Settings for [SinewTimeouts]. */
@KtorDsl
public class SinewTimeoutsConfig {
    /** The deadline to open a connection. */
    public var connect: Duration = 15.seconds

    /** The deadline for reads (`GET`, `HEAD`). */
    public var read: Duration = 30.seconds

    /** The deadline for writes (`POST`, `PUT`, `PATCH`, `DELETE`), longer because repeating one is costly. */
    public var write: Duration = 60.seconds
}

/**
 * A deadline on every request by method: [SinewTimeoutsConfig.read] for `GET` and `HEAD`, [SinewTimeoutsConfig.write]
 * for the rest, and the connect deadline always. A request that set its own `timeout { }` keeps it, so a slow
 * endpoint is configured on that call alone.
 *
 * Needs Ktor's `HttpTimeout` installed too, which applies the deadlines:
 *
 * ```kotlin
 * install(HttpTimeout)
 * install(SinewTimeouts) { read = 20.seconds }
 * ```
 */
public val SinewTimeouts: ClientPlugin<SinewTimeoutsConfig> = createClientPlugin("SinewTimeouts", ::SinewTimeoutsConfig) {
    val connect = pluginConfig.connect.inWholeMilliseconds
    val read = pluginConfig.read.inWholeMilliseconds
    val write = pluginConfig.write.inWholeMilliseconds
    require(connect > 0 && read > 0 && write > 0) { "SinewTimeouts: timeouts must be positive" }
    val client = client
    onRequest { request, _ ->
        checkNotNull(client.pluginOrNull(HttpTimeout)) { "SinewTimeouts needs install(HttpTimeout) as well" }
        if (request.getCapabilityOrNull(HttpTimeoutCapability) != null) return@onRequest
        val deadline = if (request.method in ReadMethods) read else write
        request.timeout {
            connectTimeoutMillis = connect
            requestTimeoutMillis = deadline
            socketTimeoutMillis = deadline
        }
    }
}

/** Settings for [SinewHeaders]. */
@KtorDsl
public class SinewHeadersConfig {
    /** The language for `Accept-Language`, read on every request. A blank value sends none. */
    public var locale: () -> String = { "" }

    /** Headers for every request (app version, API key, device id), read on every request. */
    public var defaults: () -> Map<String, String> = { emptyMap() }
}

/**
 * `Accept: application/json`, `Accept-Language` from [SinewHeadersConfig.locale] and the default headers, read on
 * every request so a language switch applies to the next one. A header the request set itself wins; a request that
 * asks for another `Accept` gets only that, without the JSON `ContentNegotiation` would add beside it.
 */
public val SinewHeaders: ClientPlugin<SinewHeadersConfig> = createClientPlugin("SinewHeaders", ::SinewHeadersConfig) {
    val locale = pluginConfig.locale
    val defaults = pluginConfig.defaults
    onRequest { request, _ ->
        fun putIfAbsent(name: String, value: String) {
            if (!request.headers.contains(name)) request.headers.append(name, value)
        }
        if (request.headers.contains(HttpHeaders.Accept)) request.exclude(ContentType.Application.Json)
        putIfAbsent(HttpHeaders.Accept, ContentType.Application.Json.toString())
        locale().takeIf { it.isNotBlank() }?.let { putIfAbsent(HttpHeaders.AcceptLanguage, it) }
        defaults().forEach { (name, value) -> putIfAbsent(name, value) }
    }
}

/**
 * Throws a failed response as the error `processApiCall` classifies, with its body read by [reader] and its
 * `Retry-After` parsed. Added to Ktor's own validator, which wraps every other plugin whatever the install order, so
 * it sees only the final response, after redirects and the auth layer's refresh:
 *
 * ```kotlin
 * HttpClient {
 *     expectSuccess = false
 *     HttpResponseValidator { sinewErrorBody() }
 * }
 * ```
 */
public fun HttpCallValidatorConfig.sinewErrorBody(reader: ErrorBodyReader = ErrorBodyReader.Default) {
    validateResponse { response -> if (!response.status.isSuccess()) throw response.toStatusError(reader) }
}

private suspend fun HttpResponse.toStatusError(reader: ErrorBodyReader): HttpStatusError {
    val body = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return HttpStatusError(
        status = status.value,
        errorBody = reader.read(status.value, body),
        retryAfter = parseRetryAfter(headers[HttpHeaders.RetryAfter]),
    )
}

private val RedirectStatuses = setOf(
    HttpStatusCode.MovedPermanently,
    HttpStatusCode.Found,
    HttpStatusCode.SeeOther,
    HttpStatusCode.TemporaryRedirect,
    HttpStatusCode.PermanentRedirect,
)

/** Settings for [SinewRedirect]. */
@KtorDsl
public class SinewRedirectConfig {
    /** How many redirects one request follows before its last redirect response is returned. */
    public var maxHops: Int = 5
}

/**
 * Ktor's redirect rules with a hop limit: follows `301`, `302`, `303`, `307` and `308` for `GET` and `HEAD` only, at
 * most [SinewRedirectConfig.maxHops] times, never from `https` to `http`. A hop to another host or port carries
 * neither `Authorization` nor `Cookie`. Each hop goes through the plugins installed after this one, so `SinewAuth`
 * decides each hop's token by its host.
 *
 * Ktor's own `HttpRedirect` has no hop limit and keeps cookies, and must be off:
 *
 * ```kotlin
 * HttpClient {
 *     followRedirects = false
 *     install(SinewRedirect)
 * }
 * ```
 */
public val SinewRedirect: ClientPlugin<SinewRedirectConfig> = createClientPlugin("SinewRedirect", ::SinewRedirectConfig) {
    val maxHops = pluginConfig.maxHops
    require(maxHops >= 0) { "SinewRedirect: maxHops must not be negative" }
    check(client.pluginOrNull(HttpRedirect) == null) {
        "SinewRedirect replaces Ktor's HttpRedirect: set followRedirects = false"
    }
    on(Send) { request ->
        var call = proceed(request)
        if (request.method !in ReadMethods) return@on call
        // Each hop reuses the caller's builder, so it stays tied to the caller's job and its cancellation.
        repeat(maxHops) {
            if (call.response.status !in RedirectStatuses) return@on call
            val location = call.response.headers[HttpHeaders.Location] ?: return@on call
            val from = call.request.url
            request.url.parameters.clear()
            request.url.takeFrom(location)
            if (from.protocol.isSecure() && !request.url.protocol.isSecure()) return@on call
            if (request.url.host != from.host || request.url.port != from.port) {
                request.headers.remove(HttpHeaders.Authorization)
                request.headers.remove(HttpHeaders.Cookie)
            }
            call = proceed(request)
        }
        call
    }
}

/** Settings for [SinewLog]. */
@KtorDsl
public class SinewLogConfig {
    /** Where the lines go. */
    public var logger: HttpLogger = HttpLogger.Default
}

/**
 * One line per exchange: method, host, path, status and duration, or the failure's type. Headers, bodies, query
 * strings and exception messages are never read, so no token or credential can reach the log. Install it last, so
 * each redirect hop and each replay after a refresh is its own line. Debug builds only.
 */
public val SinewLog: ClientPlugin<SinewLogConfig> = createClientPlugin("SinewLog", ::SinewLogConfig) {
    val logger = pluginConfig.logger
    on(Send) { request ->
        val url = request.url.build()
        val target = "${request.method.value} ${url.host}${url.encodedPath}"
        val started = TimeSource.Monotonic.markNow()
        try {
            proceed(request).also { call ->
                logger.log("$target → ${call.response.status.value} (${started.elapsedNow().inWholeMilliseconds} ms)")
            }
        } catch (e: Throwable) {
            logger.log("$target → failed: ${e::class.simpleName} (${started.elapsedNow().inWholeMilliseconds} ms)")
            throw e
        }
    }
}
