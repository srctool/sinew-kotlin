package com.srctool.sinew.network

import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.exclude
import io.ktor.client.plugins.timeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSecure
import io.ktor.http.takeFrom
import kotlin.time.Duration
import kotlin.time.TimeSource

internal class SinewTimeoutConfig {
    var connect: Duration = Duration.ZERO
    var read: Duration = Duration.ZERO
    var write: Duration = Duration.ZERO
}

private val ReadMethods = setOf(HttpMethod.Get, HttpMethod.Head)

/**
 * A deadline on every request: [SinewTimeoutConfig.read] for `GET` and `HEAD`, [SinewTimeoutConfig.write] for the
 * rest, and the connect deadline always. A request that set its own `timeout { }` keeps it, so a slow endpoint (an
 * upload, a report) is configured on that call alone.
 */
internal val SinewTimeout = createClientPlugin("SinewTimeout", ::SinewTimeoutConfig) {
    val connect = pluginConfig.connect.inWholeMilliseconds
    val read = pluginConfig.read.inWholeMilliseconds
    val write = pluginConfig.write.inWholeMilliseconds
    onRequest { request, _ ->
        if (request.getCapabilityOrNull(HttpTimeoutCapability) != null) return@onRequest
        val deadline = if (request.method in ReadMethods) read else write
        request.timeout {
            connectTimeoutMillis = connect
            requestTimeoutMillis = deadline
            socketTimeoutMillis = deadline
        }
    }
}

internal class SinewHeadersConfig {
    var locale: () -> String = { "" }
    var defaults: () -> Map<String, String> = { emptyMap() }
}

/**
 * `Accept: application/json`, `Accept-Language` from the locale and the app's default headers, read on every request
 * so a language switch applies to the next one. A header the request set itself wins. Installed before
 * `ContentNegotiation`, so it runs first.
 */
internal val SinewHeaders = createClientPlugin("SinewHeaders", ::SinewHeadersConfig) {
    val locale = pluginConfig.locale
    val defaults = pluginConfig.defaults
    onRequest { request, _ ->
        fun putIfAbsent(name: String, value: String) {
            if (!request.headers.contains(name)) request.headers.append(name, value)
        }
        // A request that asks for another type gets only that: ContentNegotiation would add JSON beside it.
        if (request.headers.contains(HttpHeaders.Accept)) request.exclude(ContentType.Application.Json)
        putIfAbsent(HttpHeaders.Accept, ContentType.Application.Json.toString())
        locale().takeIf { it.isNotBlank() }?.let { putIfAbsent(HttpHeaders.AcceptLanguage, it) }
        defaults().forEach { (name, value) -> putIfAbsent(name, value) }
    }
}

private val RedirectStatuses = setOf(
    HttpStatusCode.MovedPermanently,
    HttpStatusCode.Found,
    HttpStatusCode.SeeOther,
    HttpStatusCode.TemporaryRedirect,
    HttpStatusCode.PermanentRedirect,
)

internal const val MAX_REDIRECTS = 5

/**
 * Follows `301`, `302`, `303`, `307` and `308` for `GET` and `HEAD` only, at most [MAX_REDIRECTS] hops; the last
 * redirect response is returned past that, or when it has no `Location`. Each hop is sent through the rest of the
 * plugins, so the auth layer decides its token by the hop's host. A hop to another host or port never carries
 * `Authorization` or `Cookie`, and an `https` → `http` hop isn't followed.
 */
internal val SinewRedirect = createClientPlugin("SinewRedirect") {
    on(Send) { request ->
        var call = proceed(request)
        if (request.method !in ReadMethods) return@on call
        // Each hop reuses the caller's builder, so it stays tied to the caller's job and its cancellation.
        repeat(MAX_REDIRECTS) {
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

internal class SinewLogConfig {
    var logger: HttpLogger = HttpLogger.Default
}

/**
 * One line per exchange, innermost, so each redirect hop and replay is its own line: method, host, path, status and
 * duration, or the failure's type. Headers, bodies and query strings are never read.
 */
internal val SinewLog = createClientPlugin("SinewLog", ::SinewLogConfig) {
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
