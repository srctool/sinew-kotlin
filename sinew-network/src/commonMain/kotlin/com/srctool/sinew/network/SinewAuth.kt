package com.srctool.sinew.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.KtorDsl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * One refresh at a time. Every 401 that arrives while a refresh runs waits for that refresh's outcome instead of
 * starting its own: with rotating refresh tokens, a second refresh would invalidate the first.
 *
 * The refresh runs in [scope], owned by the client, so cancelling the request that started it cancels only that
 * request's wait. Ending the session on [RefreshOutcome.Rejected] is the token source's own job: only it knows whether
 * the session that refresh belonged to is still the current one.
 */
internal class SingleFlightRefresher(private val source: TokenSource, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var inFlight: CompletableDeferred<RefreshOutcome>? = null
    private var job: Job? = null

    /** Joins the running refresh, or starts one. A refresh that threw rethrows here, in every waiter. */
    suspend fun refresh(): RefreshOutcome {
        val outcome = mutex.withLock {
            inFlight ?: CompletableDeferred<RefreshOutcome>().also { started ->
                inFlight = started
                job = scope.launch { run(started) }
            }
        }
        return outcome.await()
    }

    private suspend fun run(outcome: CompletableDeferred<RefreshOutcome>) {
        try {
            val result = source.refresh()
            finish(outcome) { complete(result) }
        } catch (e: CancellationException) {
            throw e                                                   // reset() already answered the waiters
        } catch (e: Throwable) {
            finish(outcome) { completeExceptionally(e) }
        }
    }

    private suspend fun finish(outcome: CompletableDeferred<RefreshOutcome>, answer: CompletableDeferred<RefreshOutcome>.() -> Unit) {
        mutex.withLock {
            if (inFlight === outcome) {
                inFlight = null
                job = null
            }
        }
        outcome.answer()
    }

    /** Sign-out: stops the running refresh and fails its waiters as [RefreshOutcome.Rejected]. */
    suspend fun reset() = mutex.withLock {
        job?.cancel()
        inFlight?.complete(RefreshOutcome.Rejected)
        inFlight = null
        job = null
    }

    fun close() {
        inFlight?.complete(RefreshOutcome.Rejected)
        scope.cancel()
    }
}

/** Settings for [SinewAuth]. */
@KtorDsl
public class SinewAuthConfig {
    /** Where the tokens live and how they refresh. Required. */
    public var tokenSource: TokenSource? = null

    /**
     * The backend's base URL: only its host and port receive the token, and [anonymousPaths] are matched relative to
     * its path. Leave it unset to use the one from `sinewBackend(…)`; set it when the client sets `defaultRequest`
     * itself.
     */
    public var baseUrl: String? = null

    /**
     * Paths that never get a token and never trigger a refresh (sign-in, register, OTP, password reset, the refresh
     * call), matched exactly, relative to [baseUrl]'s path: with `https://api.example.com/v1`, `/auth/login` matches
     * `/v1/auth/login`, not `/v1/auth/login/history`.
     */
    public var anonymousPaths: Set<String> = emptySet()

    internal var refreshContext: CoroutineContext = Dispatchers.Default
}

/** Set on a request whose `Authorization` the auth layer wrote, so a later hop can tell it from the caller's own. */
private val Attached = AttributeKey<Unit>("SinewAuthAttached")

/** Set on a replayed request: a second 401 on it fails instead of refreshing again. */
private val Replayed = AttributeKey<Unit>("SinewAuthReplayed")

private val RefresherKey = AttributeKey<SingleFlightRefresher>("SinewAuthRefresher")

/**
 * The auth layer. For a request to the client's own host and port, outside the anonymous paths, it attaches the
 * current access token and handles a 401:
 *
 * - sent with an older token than the current one: a refresh already finished, so it replays at once;
 * - sent with a token that has since been cleared: the user signed out, so it returns the 401 without refreshing;
 * - otherwise it joins or starts the single refresh, then replays once with the new token ([RefreshOutcome.Refreshed]),
 *   returns the 401 for the guard to classify as `UnauthorizedException` ([RefreshOutcome.Rejected]), or throws the
 *   cause, keeping the session ([RefreshOutcome.Unavailable]).
 *
 * Installed on its own or with the other Sinew plugins, in any order: Ktor's redirect and validator always wrap it,
 * so each redirect hop gets its own token decision, and `HttpResponseValidator { sinewErrorBody() }` sees the
 * response only after a refresh. Don't install Ktor's `Auth` as well: both would handle the same 401s.
 *
 * ```kotlin
 * sinewBackend("https://api.example.com/v1")
 * install(SinewAuth) {
 *     tokenSource = tokens
 *     anonymousPaths = setOf("/auth/login", "/auth/refresh")
 * }
 * ```
 *
 * On sign-out, call `SinewHttp.resetAuth(client)` and `tokenSource.clear()`.
 *
 * A replayed request that gets 401 again, a request to another host and an anonymous path return their 401 as is.
 * A one-shot streamed body can't be sent twice: it still refreshes, so the app's retry carries the new token, but it
 * isn't replayed. A request with its own `Authorization` header is left alone.
 */
public val SinewAuth: ClientPlugin<SinewAuthConfig> = createClientPlugin("SinewAuth", ::SinewAuthConfig) {
    val source = checkNotNull(pluginConfig.tokenSource) { "SinewAuth needs a tokenSource" }
    val explicitBase = pluginConfig.baseUrl?.let(::Url)
    val client = client
    // sinewBackend(…) may be called after install(SinewAuth), so the base is read on the first request.
    val base by lazy {
        explicitBase ?: client.sinewBackendUrl() ?: error("SinewAuth needs sinewBackend(…) on the client, or its own baseUrl")
    }
    val basePath by lazy { base.encodedPath.trimEnd('/') }
    val anonymousPaths = pluginConfig.anonymousPaths
    val relative = anonymousPaths.filterNot { it.startsWith("/") }
    require(relative.isEmpty()) { "SinewAuth: anonymous paths must start with '/': $relative" }
    val refresher = SingleFlightRefresher(source, CoroutineScope(SupervisorJob() + pluginConfig.refreshContext))
    client.attributes.put(RefresherKey, refresher)
    onClose { refresher.close() }

    fun HttpRequestBuilder.ownsToken(): Boolean {
        val url = url.build()
        if (url.host != base.host || url.port != base.port) return false
        val path = url.encodedPath
        val relative = if (path.startsWith("$basePath/")) path.removePrefix(basePath) else path
        return relative !in anonymousPaths
    }

    fun HttpRequestBuilder.attach(token: String?) {
        attributes.put(Attached, Unit)
        if (token == null) headers.remove(HttpHeaders.Authorization) else headers[HttpHeaders.Authorization] = "Bearer $token"
    }

    on(Send) { request ->
        val callersOwn = request.headers.contains(HttpHeaders.Authorization) && !request.attributes.contains(Attached)
        if (callersOwn || !request.ownsToken()) return@on proceed(request)

        val sentWith = source.accessToken()
        request.attach(sentWith)
        val call = proceed(request)
        if (call.response.status != HttpStatusCode.Unauthorized) return@on call
        if (request.attributes.contains(Replayed)) return@on call

        val current = source.accessToken()
        if (current == null && sentWith != null) return@on call                    // signed out meanwhile: never refresh
        val outcome = if (current != null && current != sentWith) RefreshOutcome.Refreshed else refresher.refresh()
        when (outcome) {
            RefreshOutcome.Refreshed -> {
                if (request.body is OutgoingContent.ReadChannelContent) return@on call   // can't be sent twice: the app retries
                val token = source.accessToken() ?: return@on call
                request.attributes.put(Replayed, Unit)
                request.attach(token)
                proceed(request)
            }
            RefreshOutcome.Rejected -> call
            is RefreshOutcome.Unavailable -> throw outcome.cause
        }
    }
}

/** The client's refresher, for `SinewHttp.resetAuth`. */
internal fun HttpClient.refresher(): SingleFlightRefresher? = attributes.getOrNull(RefresherKey)
