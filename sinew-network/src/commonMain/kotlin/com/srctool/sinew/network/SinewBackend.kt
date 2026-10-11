package com.srctool.sinew.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.Url
import io.ktor.util.AttributeKey

/**
 * The backend this client talks to, written once: it sets Ktor's `defaultRequest` URL, so API classes use paths
 * relative to it (`get("orders")`), and records it for the Sinew plugins that need it: `SinewAuth` sends the token
 * only to its host and port, and matches anonymous paths relative to its path.
 *
 * ```kotlin
 * HttpClient(OkHttp) {
 *     sinewBackend("https://api.shop.com/v1")
 *     install(SinewAuth) { tokenSource = tokens; anonymousPaths = setOf("/auth/login") }
 * }
 * ```
 *
 * A trailing slash is added, so `get("orders")` resolves to `…/v1/orders`; a leading `/` would replace `/v1`.
 */
public fun HttpClientConfig<*>.sinewBackend(baseUrl: String) {
    require(baseUrl.isNotBlank()) { "sinewBackend: baseUrl is blank" }
    val withSlash = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    defaultRequest { url(withSlash) }
    install(SinewBackendRecord) { url = Url(withSlash) }
}

internal class SinewBackendRecordConfig {
    var url: Url? = null
}

private val BackendKey = AttributeKey<Url>("SinewBackend")

private val SinewBackendRecord = createClientPlugin("SinewBackend", ::SinewBackendRecordConfig) {
    client.attributes.put(BackendKey, checkNotNull(pluginConfig.url))
}

/** The URL set by [sinewBackend], or `null`. */
internal fun HttpClient.sinewBackendUrl(): Url? = attributes.getOrNull(BackendKey)
