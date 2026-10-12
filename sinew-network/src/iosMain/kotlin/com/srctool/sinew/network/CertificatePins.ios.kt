package com.srctool.sinew.network

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.engine.darwin.certificates.CertificatePinner

/**
 * Pins [host] to [pins] (`sha256/…` public-key hashes) on this Darwin client, through Ktor's port of OkHttp's
 * `CertificatePinner`: the server's chain is checked when URLSession raises its authentication challenge, before the
 * request is sent, and a chain with none of the pinned keys cancels the connection. `processApiCall` reports that as
 * an `UnexpectedException`. Pin your authority's or intermediate's key, plus a backup.
 *
 * For a client built with `SinewHttp.client(config, tokens, Darwin) { engine { pinCertificates(host, pins) } }`;
 * the preset applies `SinewConfig.certificatePins` itself.
 */
public fun DarwinClientEngineConfig.pinCertificates(host: String, pins: Set<String>) {
    requireValidPins(pins)
    require(pins.isNotEmpty()) { "pinCertificates: no pins: every request to $host would fail" }
    handleChallenge(CertificatePinner.Builder().add(host, *pins.toTypedArray()).build())
}

internal actual fun HttpClientEngineConfig.installCertificatePins(host: String, pins: Set<String>): Boolean {
    if (this !is DarwinClientEngineConfig) return false
    pinCertificates(host, pins)
    return true
}
