package com.srctool.sinew.network

import io.ktor.client.engine.HttpClientEngineConfig

/** The browser owns TLS: pins can't be applied, and are ignored, as documented on `SinewConfig.certificatePins`. */
internal actual fun HttpClientEngineConfig.installCertificatePins(host: String, pins: Set<String>): Boolean = true
