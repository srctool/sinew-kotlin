package com.srctool.sinew.network

import io.ktor.client.engine.HttpClientEngineConfig
import kotlin.io.encoding.Base64

/**
 * Checks pins written as OkHttp and Ktor's Darwin engine expect them: `sha256/` and the Base64 SHA-256 of a
 * certificate's public key (its SubjectPublicKeyInfo). Throws an [IllegalArgumentException] naming each bad pin.
 */
internal fun requireValidPins(pins: Set<String>) {
    val bad = pins.filterNot { pin ->
        pin.startsWith("sha256/") &&
            runCatching { Base64.decode(pin.removePrefix("sha256/")).size == 32 }.getOrDefault(false)
    }
    require(bad.isEmpty()) { "Pins must be sha256/ and the Base64 SHA-256 of a public key: $bad" }
}

/**
 * Pins [host] on this engine, when the engine can: OkHttp on Android and the JVM, Darwin on iOS. In the browser
 * (wasm) it does nothing and returns `true`: the browser owns TLS. Any other engine returns `false`, so a pinned
 * client never goes unpinned silently.
 */
internal expect fun HttpClientEngineConfig.installCertificatePins(host: String, pins: Set<String>): Boolean
