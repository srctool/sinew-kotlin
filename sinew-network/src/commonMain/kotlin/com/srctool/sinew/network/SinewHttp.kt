package com.srctool.sinew.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * The optional preset: a client with every Sinew plugin installed from one [SinewConfig]. Skip it to install only
 * the plugins an app needs on its own `HttpClient`: [sinewBackend], [SinewAuth], [SinewHeaders], [SinewTimeouts],
 * [SinewRedirect], [SinewLog] and `HttpResponseValidator { sinewErrorBody() }`.
 */
public object SinewHttp {
    /**
     * A Ktor client for [config]'s backend, on the platform engine: OkHttp on Android and the JVM, Darwin on iOS, Js
     * on wasm. The app writes its API classes directly on it, with paths **relative** to `baseUrl` (`get("orders")`):
     * a leading `/` would replace `baseUrl`'s own path.
     *
     * Every request gets a deadline by method, the JSON and language headers, and the app's default headers. A
     * non-2xx response is read with `config.errorBodyReader` and thrown for `processApiCall` to classify, after
     * redirects are followed. Requests are logged, redacted, only when `config.logHttp` is on.
     *
     * The auth layer attaches [tokenSource]'s access token to requests for `baseUrl`'s host, outside
     * `config.anonymousPaths`, and refreshes once for any number of concurrent 401s. On sign-out, call [resetAuth]
     * and `tokenSource.clear()`.
     *
     * @param logger where the log goes when `config.logHttp` is on.
     * @param configure the app's own settings, applied last. Don't install Ktor's `HttpRedirect`, `Auth` or
     *   `Logging`: Sinew's own take their place. To configure the engine (certificate pinning), pass it explicitly.
     */
    public fun client(
        config: SinewConfig,
        tokenSource: TokenSource,
        logger: HttpLogger = HttpLogger.Default,
        configure: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = HttpClient(platformEngine()) {
        installSinew(config, tokenSource, logger, Dispatchers.Default)
        configure()
    }

    /**
     * The same client on an explicit [engine], so [configure] can reach the engine's own settings:
     *
     * ```kotlin
     * SinewHttp.client(config, OkHttp) {
     *     engine { config { certificatePinner(pins) } }
     * }
     * ```
     */
    public fun <T : HttpClientEngineConfig> client(
        config: SinewConfig,
        tokenSource: TokenSource,
        engine: HttpClientEngineFactory<T>,
        logger: HttpLogger = HttpLogger.Default,
        configure: HttpClientConfig<T>.() -> Unit = {},
    ): HttpClient = client(config, tokenSource, engine, logger, Dispatchers.Default, configure)

    /** The same, with the context refreshes run in: a test dispatcher in tests. */
    internal fun <T : HttpClientEngineConfig> client(
        config: SinewConfig,
        tokenSource: TokenSource,
        engine: HttpClientEngineFactory<T>,
        logger: HttpLogger,
        refreshContext: CoroutineContext,
        configure: HttpClientConfig<T>.() -> Unit,
    ): HttpClient = HttpClient(engine) {
        installSinew(config, tokenSource, logger, refreshContext)
        configure()
    }

    /**
     * Sign-out: stops [client]'s running refresh and fails the requests waiting on it with
     * `UnauthorizedException`, so nothing carries into the next session. Call it with `tokenSource.clear()`, which
     * makes sure a refresh finishing late stores nothing.
     */
    public suspend fun resetAuth(client: HttpClient) {
        client.refresher()?.reset()
    }
}

private fun HttpClientConfig<*>.installSinew(
    config: SinewConfig,
    tokenSource: TokenSource,
    logger: HttpLogger,
    refreshContext: CoroutineContext,
) {
    expectSuccess = false
    followRedirects = false
    install(ContentNegotiation) { json(SinewJson) }
    install(HttpTimeout)
    sinewBackend(config.baseUrl)
    install(SinewHeaders) {
        locale = config.locale
        defaults = config.defaultHeaders
    }
    install(SinewTimeouts) {
        connect = config.connectTimeout
        read = config.readTimeout
        write = config.writeTimeout
    }
    HttpResponseValidator { sinewErrorBody(config.errorBodyReader) }
    install(SinewRedirect)
    install(SinewAuth) {
        this.tokenSource = tokenSource
        anonymousPaths = config.anonymousPaths
        this.refreshContext = refreshContext
    }
    if (config.logHttp) install(SinewLog) { this.logger = logger }
}

/** OkHttp on Android and the JVM, Darwin on iOS, Js on wasm. */
internal expect fun platformEngine(): HttpClientEngineFactory<*>
