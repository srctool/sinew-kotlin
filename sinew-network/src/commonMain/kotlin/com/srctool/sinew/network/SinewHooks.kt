package com.srctool.sinew.network

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.isSaved
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.KtorDsl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Devtools' way into a client: fault injection, a host override and recording. Only devtools implements it. Every
 * method has a default that leaves the request alone, and [None] is what a client gets without devtools, so production
 * code paths never change.
 */
public interface SinewHttpHooks {
    /** Called once, when a client is built with these hooks: devtools keeps it to replay records and expire tokens. */
    public fun attached(client: HttpClient) {}

    /** Called once, when that client is closed: devtools forgets it, so a closed client is never replayed through. */
    public fun detached(client: HttpClient) {}

    /** The base URL to send to instead of the client's own (devtools' API host page), or `null` to keep it. */
    public fun hostOverride(): String? = null

    /** A fault to apply to a request instead of sending it as it is, or `null` to send it. */
    public suspend fun fault(method: String, url: Url): FaultDecision? = null

    /** Called once per exchange that reached the network or a fault, with the raw values: devtools redacts them. */
    public fun record(exchange: HttpExchange) {}

    /** The hooks Sinew provides. */
    public companion object {
        /** Leaves every request alone. The default. */
        public val None: SinewHttpHooks = object : SinewHttpHooks {}
    }
}

/**
 * What a fault does to a request, inside the auth layer: wait [delay], then fail as a dropped connection ([drop]),
 * answer [status] with [body] without reaching the network, or, with neither, send the request after the delay.
  *
  * @property delay How long to wait first, or `null` for no wait.
  * @property status The HTTP status to answer, or `null` to send the request.
  * @property body The response body that goes with [status].
  * @property drop Whether to fail as a dropped connection.
 */
public class FaultDecision(
    public val delay: Duration? = null,
    public val status: Int? = null,
    public val body: String? = null,
    public val drop: Boolean = false,
) {
    init {
        require(status == null || status in 100..599) { "status must be an HTTP status, was $status" }
        require(!(drop && status != null)) { "a fault either drops the connection or answers a status, not both" }
    }
}

/**
 * One request and its response, as sent: after the auth layer and the host override. Values are **not** redacted;
 * only devtools receives this, and it redacts before storing anything.
 */
public class HttpExchange(
    /** The client that sent it, to replay through. */
    public val client: HttpClient,
    /** The HTTP method. */
    public val method: String,
    /** The URL as sent, after a host override. */
    public val url: String,
    /** The URL the caller asked for, before a host override: what a replay asks for again. */
    public val requestedUrl: String,
    /** The request headers as sent, by name. */
    public val requestHeaders: Map<String, List<String>>,
    /** The request body, or `null` when there was none or it was streamed ([requestBodyStreamed]). */
    public val requestBody: ByteArray?,
    /** Whether the request body was streamed, so it couldn't be read or sent again. */
    public val requestBodyStreamed: Boolean,
    /** The response status, or `null` when the request failed before a response. */
    public val status: Int?,
    /** The response headers, by name. */
    public val responseHeaders: Map<String, List<String>>,
    /** The response body, or `null` when there was none or it was streamed. */
    public val responseBody: ByteArray?,
    /** When the request started. */
    public val startedAt: Instant,
    /** How long it took. */
    public val duration: Duration,
    /** The failure when the request did not get a response, otherwise `null`. */
    public val error: Throwable?,
    /** The names of the client's default headers when it was sent: devtools redacts them. */
    public val defaultHeaderNames: Set<String>,
    /** The request's attributes, where devtools marks its own replays. */
    public val attributes: Attributes,
)

/** Settings for [SinewHooks]. */
@KtorDsl
public class SinewHooksConfig {
    /** The hooks to call. [SinewHttpHooks.None] by default. */
    public var hooks: SinewHttpHooks = SinewHttpHooks.None

    /** The client's default headers, read on every request: their names are passed to [SinewHttpHooks.record]. */
    public var defaults: () -> Map<String, String> = { emptyMap() }

    /** The backend's base URL, for the host override. Unset: the one from `sinewBackend(…)`. */
    public var baseUrl: String? = null
}

/** Set by `SinewHttp.debugExpireToken`: the next request that carries a token sends an invalid one. */
private val ExpireNextToken = AttributeKey<Unit>("SinewExpireNextToken")

/** An invalid token, sent once after `SinewHttp.debugExpireToken`. */
internal const val EXPIRED_TOKEN: String = "sinew-devtools-expired"

/** A connection dropped by a devtools fault. The guard treats it as no connection, like a real one. */
internal class InjectedConnectionDrop : Exception("Connection dropped by a devtools fault rule")

internal fun HttpClient.expireNextToken() {
    attributes.put(ExpireNextToken, Unit)
}

/**
 * Devtools' hook point, installed **inside** `SinewAuth` so a forced 401 runs the real refresh, and a replay after a
 * refresh is recorded on its own. With [SinewHttpHooks.None] it only serves `SinewHttp.debugExpireToken`.
 */
@OptIn(InternalAPI::class)
public val SinewHooks: ClientPlugin<SinewHooksConfig> = createClientPlugin("SinewHooks", ::SinewHooksConfig) {
    val hooks = pluginConfig.hooks
    val defaults = pluginConfig.defaults
    val explicitBase = pluginConfig.baseUrl?.let(::Url)
    val client = client
    val base by lazy { explicitBase ?: client.sinewBackendUrl() }
    hooks.attached(client)
    onClose { hooks.detached(client) }

    on(Send) { request ->
        if (client.attributes.contains(ExpireNextToken) && request.headers.contains(HttpHeaders.Authorization)) {
            client.attributes.remove(ExpireNextToken)
            request.headers[HttpHeaders.Authorization] = "Bearer $EXPIRED_TOKEN"
        }
        if (hooks === SinewHttpHooks.None) return@on proceed(request)

        val requestedUrl = request.url.buildString()
        hooks.hostOverride()?.let { override -> base?.let { request.overrideHost(it, Url(override)) } }
        val started = Clock.System.now()
        val mark = TimeSource.Monotonic.markNow()
        val content = request.body as? OutgoingContent
        val requestBody = (content as? OutgoingContent.ByteArrayContent)?.bytes()
        val streamed = content != null && content !is OutgoingContent.ByteArrayContent && content !is OutgoingContent.NoContent

        fun exchange(status: Int?, responseHeaders: Map<String, List<String>>, responseBody: ByteArray?, error: Throwable?) =
            HttpExchange(
                client = client,
                method = request.method.value,
                url = request.url.buildString(),
                requestedUrl = requestedUrl,
                requestHeaders = request.headers.build().toMap() +
                    (content?.contentType?.let { mapOf(HttpHeaders.ContentType to listOf(it.toString())) } ?: emptyMap()),
                requestBody = requestBody,
                requestBodyStreamed = streamed,
                status = status,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                startedAt = started,
                duration = mark.elapsedNow(),
                error = error,
                defaultHeaderNames = defaults().keys,
                attributes = request.attributes,
            )

        try {
            val fault = hooks.fault(request.method.value, request.url.build())
            fault?.delay?.let { delay(it) }
            val call = when {
                fault?.drop == true -> throw InjectedConnectionDrop()
                fault?.status != null -> {
                    val body = fault.body.orEmpty().encodeToByteArray()
                    val headers = if (body.isEmpty()) Headers.Empty else headersOf(HttpHeaders.ContentType, "application/json")
                    HttpClientCall(
                        client,
                        request.build(),
                        HttpResponseData(
                            HttpStatusCode.fromValue(fault.status),
                            GMTDate(),
                            headers,
                            HttpProtocolVersion.HTTP_1_1,
                            ByteReadChannel(body),
                            currentCoroutineContext() + Job(request.executionContext),   // Ktor completes it, as an engine's
                        ),
                    ).also { hooks.record(exchange(fault.status, headers.toMap(), body, null)) }
                }
                else -> proceed(request).also { call ->
                    val response = call.response
                    val body = if (response.isSaved) response.readRawBytes() else null
                    hooks.record(exchange(response.status.value, response.headers.toMap(), body, null))
                }
            }
            call
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hooks.record(exchange(null, emptyMap(), null, e))
            throw e
        }
    }
}

private fun Headers.toMap(): Map<String, List<String>> = names().associateWith { getAll(it).orEmpty() }

/** Sends a request for the client's own backend to [override] instead, keeping the path below the base path. */
private fun HttpRequestBuilder.overrideHost(base: Url, override: Url) {
    val built = url.build()                                   // a builder's port is 0 until it's resolved
    if (built.host != base.host || built.port != base.port) return
    val basePath = base.encodedPath.trimEnd('/')
    val path = built.encodedPath
    if (path != basePath && !path.startsWith("$basePath/")) return
    url.protocol = override.protocol
    url.host = override.host
    url.port = override.port
    url.encodedPath = override.encodedPath.trimEnd('/') + path.removePrefix(basePath)
}
