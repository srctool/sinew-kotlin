package com.srctool.sinew.network

import kotlinx.datetime.format.DateTimeComponents
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A response with a non-2xx status, thrown by the client after it read the error body with its own
 * [ErrorBodyReader]. The guard's handlers classify it by [status].
 *
 * Internal: it only travels from the client to `processApiCall`, which turns it into a `SinewException`. Apps never
 * see it. The exception's message names only the status: the body may hold personal data.
 *
 * @property errorBody what the client's reader made of the body, or `null` when it was unreadable.
 * @property retryAfter the `Retry-After` header as a wait, for a 429 or a 503.
 */
internal class HttpStatusError(
    val status: Int,
    val errorBody: ErrorBody?,
    val retryAfter: Duration?,
) : Exception("HTTP $status")

/**
 * The wait a `Retry-After` header asks for: delay-seconds (`120`) or an HTTP date (`Wed, 21 Oct 2015 07:28:00 GMT`),
 * counted from [now]. A date in the past is a zero wait. Anything else, including the obsolete RFC 850 and asctime
 * date forms, is `null`, as if the header were absent.
 */
internal fun parseRetryAfter(value: String?, now: Instant = Clock.System.now()): Duration? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.all { it.isDigit() }) return text.toLongOrNull()?.seconds
    val date = try {
        DateTimeComponents.Formats.RFC_1123.parse(text).toInstantUsingOffset()
    } catch (_: IllegalArgumentException) {
        return null
    }
    return (date - now).coerceAtLeast(Duration.ZERO)
}
