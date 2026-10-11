package com.srctool.sinew.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException

/** Builds Sinew's HTTP clients: one per backend, each from its own [SinewConfig]. */
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
     * @param logger where the log goes when `config.logHttp` is on.
     * @param configure the app's own settings, applied last. Don't install Ktor's `HttpRedirect`, `Auth` or
     *   `Logging`: Sinew's own take their place. To configure the engine (certificate pinning), pass it explicitly.
     */
    public fun client(
        config: SinewConfig,
        logger: HttpLogger = HttpLogger.Default,
        configure: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = HttpClient(platformEngine()) {
        installSinew(config, logger)
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
        engine: HttpClientEngineFactory<T>,
        logger: HttpLogger = HttpLogger.Default,
        configure: HttpClientConfig<T>.() -> Unit = {},
    ): HttpClient = HttpClient(engine) {
        installSinew(config, logger)
        configure()
    }
}

private fun HttpClientConfig<*>.installSinew(config: SinewConfig, logger: HttpLogger) {
    expectSuccess = false
    followRedirects = false
    install(SinewHeaders) {
        locale = config.locale
        defaults = config.defaultHeaders
    }
    install(ContentNegotiation) { json(SinewJson) }
    install(HttpTimeout)
    defaultRequest { url(config.baseUrl.withTrailingSlash()) }
    install(SinewTimeout) {
        connect = config.connectTimeout
        read = config.readTimeout
        write = config.writeTimeout
    }
    // The validator is a default plugin, so it already wraps every plugin below: it sees only the final response,
    // after redirects.
    HttpResponseValidator {
        validateResponse { response -> if (!response.status.isSuccess()) throw response.toStatusError(config) }
    }
    install(SinewRedirect)
    if (config.logHttp) install(SinewLog) { this.logger = logger }
}

/** A relative path resolves under `baseUrl`'s own path only when that path ends with `/`. */
private fun String.withTrailingSlash(): String = if (endsWith("/")) this else "$this/"

private suspend fun HttpResponse.toStatusError(config: SinewConfig): HttpStatusError {
    val body = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return HttpStatusError(
        status = status.value,
        errorBody = config.errorBodyReader.read(status.value, body),
        retryAfter = parseRetryAfter(headers[HttpHeaders.RetryAfter]),
    )
}

/** OkHttp on Android and the JVM, Darwin on iOS, Js on wasm. */
internal expect fun platformEngine(): HttpClientEngineFactory<*>
