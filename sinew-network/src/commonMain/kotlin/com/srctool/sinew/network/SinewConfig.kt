package com.srctool.sinew.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What an app configures for one backend. Build one client per backend from it.
 *
 * [locale] and [defaultHeaders] are read on every request, so a language switch or a new device id applies to the
 * next request without rebuilding the client. Every header name in [defaultHeaders] is redacted by devtools.
 *
 * @property baseUrl the backend's base URL, from the app's build configuration. Its host is the only host that
 *   receives the user's token.
 * @property connectTimeout the deadline to open a connection.
 * @property readTimeout the deadline for reads (`GET`, `HEAD`).
 * @property writeTimeout the deadline for writes (`POST`, `PUT`, `PATCH`, `DELETE`), longer because repeating one is
 *   costly.
 * @property logHttp whether to log method, path, status and duration, redacted. Debug builds only: never on in release.
 * @property anonymousPaths paths that never get a token and never trigger a refresh (sign-in, register, OTP, password
 *   reset, the refresh call). Matched relative to [baseUrl]'s path: with `https://api.example.com/v1`, `/auth/login`
 *   matches `/v1/auth/login`.
 * @property errorBodyReader how this backend's error bodies read.
 * @property certificatePins SHA-256 public-key pins (`sha256/…`) for [baseUrl]'s host, applied by the preset at the
 *   TLS handshake on OkHttp and Darwin: a chain with none of them fails before anything is sent, as a reported
 *   `UnexpectedException`. Pin your authority's or intermediate's key plus a backup, so a renewal doesn't lock users
 *   out. Empty means no pinning. Ignored in the browser (wasm), which owns TLS.
 */
public class SinewConfig(
    public val baseUrl: String,
    public val locale: () -> String,
    public val defaultHeaders: () -> Map<String, String> = { emptyMap() },
    public val connectTimeout: Duration = 15.seconds,
    public val readTimeout: Duration = 30.seconds,
    public val writeTimeout: Duration = 60.seconds,
    public val logHttp: Boolean = false,
    public val anonymousPaths: Set<String> = emptySet(),
    public val errorBodyReader: ErrorBodyReader = ErrorBodyReader.Default,
    public val certificatePins: Set<String> = emptySet(),
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl is blank" }
        require(connectTimeout.isPositive() && readTimeout.isPositive() && writeTimeout.isPositive()) {
            "Timeouts must be positive: connect $connectTimeout, read $readTimeout, write $writeTimeout"
        }
        val relative = anonymousPaths.filterNot { it.startsWith("/") }
        require(relative.isEmpty()) { "Anonymous paths must start with '/': $relative" }
        requireValidPins(certificatePins)
    }
}
