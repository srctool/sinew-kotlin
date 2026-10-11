package com.srctool.sinew.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RetryAfterTest {
    private val now = Instant.parse("2015-10-21T07:26:00Z")

    @Test
    fun parsesDelaySeconds() {
        assertEquals(120.seconds, parseRetryAfter("120", now))
        assertEquals(Duration.ZERO, parseRetryAfter("0", now))
        assertEquals(5.seconds, parseRetryAfter(" 5 ", now))
    }

    @Test
    fun parsesAnHttpDateFromNow() = assertEquals(2.minutes, parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now))

    @Test
    fun aDateInThePastIsAZeroWait() = assertEquals(Duration.ZERO, parseRetryAfter("Wed, 21 Oct 2015 07:20:00 GMT", now))

    @Test
    fun anythingElseIsAbsent() {
        assertNull(parseRetryAfter(null, now))
        assertNull(parseRetryAfter("", now))
        assertNull(parseRetryAfter("-5", now))
        assertNull(parseRetryAfter("1.5", now))
        assertNull(parseRetryAfter("soon", now))
        assertNull(parseRetryAfter("99999999999999999999", now))
        assertNull(parseRetryAfter("Wednesday, 21-Oct-15 07:28:00 GMT", now)) // obsolete RFC 850 form
        assertNull(parseRetryAfter("Wed Oct 21 07:28:00 2015", now)) // obsolete asctime form
    }

    @Test
    fun theErrorNamesOnlyItsStatus() {
        val error = HttpStatusError(422, ErrorBody("jane@example.com is taken", null), null)
        assertEquals("HTTP 422", error.message)
    }
}
