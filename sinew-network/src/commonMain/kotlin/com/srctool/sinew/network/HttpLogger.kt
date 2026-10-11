package com.srctool.sinew.network

/**
 * Where a client's request log goes when `SinewConfig.logHttp` is on: one line per exchange, with the method, host,
 * path, status and duration. Never a header, a body or a query string, so no token, cookie or credential can reach
 * it. Pass the app's logger (Timber, Kermit) in place of [Default].
 */
public fun interface HttpLogger {
    /** Writes one [line]. */
    public fun log(line: String)

    public companion object {
        /** Prints to standard output (Logcat on Android). */
        public val Default: HttpLogger = HttpLogger { println("SinewHttp: $it") }
    }
}
