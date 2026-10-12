package com.srctool.sinew.network

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.CertificatePinner

/**
 * Pins [host] to [pins] (`sha256/…` public-key hashes) on this OkHttp client: OkHttp checks the chain the server
 * presents during the handshake, before the request is written, and a chain with none of the pinned keys fails with
 * `SSLPeerUnverifiedException`, which `processApiCall` reports as an `UnexpectedException`. Pin your authority's or
 * intermediate's key, plus a backup, so a renewed certificate doesn't lock users out.
 *
 * For a client built with `SinewHttp.client(config, tokens, OkHttp) { engine { pinCertificates(host, pins) } }`;
 * the preset applies `SinewConfig.certificatePins` itself.
 */
public fun OkHttpConfig.pinCertificates(host: String, pins: Set<String>) {
    requireValidPins(pins)
    require(pins.isNotEmpty()) { "pinCertificates: no pins: every request to $host would fail" }
    config { certificatePinner(CertificatePinner.Builder().add(host, *pins.toTypedArray()).build()) }
}

internal actual fun HttpClientEngineConfig.installCertificatePins(host: String, pins: Set<String>): Boolean {
    if (this !is OkHttpConfig) return false
    pinCertificates(host, pins)
    return true
}
