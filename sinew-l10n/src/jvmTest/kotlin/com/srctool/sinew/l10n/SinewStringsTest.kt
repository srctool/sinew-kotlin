package com.srctool.sinew.l10n

import com.srctool.sinew.exception.ConflictException
import com.srctool.sinew.exception.ForbiddenException
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.NotFoundException
import com.srctool.sinew.exception.PollingTimeOutException
import com.srctool.sinew.exception.RateLimitedException
import com.srctool.sinew.exception.RequestTimeOutException
import com.srctool.sinew.exception.ServerException
import com.srctool.sinew.exception.ServiceUnavailableException
import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.UnauthorizedException
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer.Repository
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The resource environment for [language]. Its constructor is internal to Compose, so this switches the JVM's default
 * locale, which the desktop system environment follows, and restores it.
 */
internal fun environment(language: String): ResourceEnvironment {
    val previous = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag(language))
    try {
        return getSystemResourceEnvironment()
    } finally {
        Locale.setDefault(previous)
    }
}

class SinewStringsTest {
    private val english = environment("en")
    private val indonesian = environment("id")

    // One exception per local key, with the arguments its message takes.
    private val localMessages: List<SinewException> = listOf(
        NoInternetConnectionException("ORD", Repository, "GO"),
        RequestTimeOutException("ORD", Repository, "GO"),
        ValidationException("ORD", Repository, "GO"),
        UnauthorizedException("ORD", Repository, "GO"),
        ForbiddenException("ORD", Repository, "GO"),
        NotFoundException("ORD", Repository, "GO"),
        ConflictException("ORD", Repository, "GO"),
        RateLimitedException("ORD", Repository, "GO", retryAfter = 30.seconds),
        RateLimitedException("ORD", Repository, "GO", retryAfter = null),
        ServerException("ORD", Repository, "GO", status = 500),
        ServiceUnavailableException("ORD", Repository, "GO", status = 503),
        UnexpectedException("ORD", Repository, "GO"),
        StorageFullException("ORD", Repository, "GO"),
        PollingTimeOutException("ORD", Repository, "GO"),
    )

    private val SinewException.local get() = errorMessage as ErrorMessage.Local

    @Test
    fun everyBuiltInKeyIsTranslated() {
        assertEquals(localMessages.map { it.local.key }.toSet(), SinewStrings.keys)
        assertEquals(14, SinewStrings.keys.size)
    }

    @Test
    fun englishResourcesMatchTheExceptionFallbacks() = runTest {
        // A missing translation and the English resource must read the same.
        for (e in localMessages) {
            assertEquals(e.message, SinewStrings.resolve(e.local.key, e.local.args, english), e.local.key)
        }
    }

    @Test
    fun everyKeyHasIndonesian() = runTest {
        for (e in localMessages) {
            val text = assertNotNull(SinewStrings.resolve(e.local.key, e.local.args, indonesian), e.local.key)
            assertNotEquals(e.message, text, "${e.local.key} is not translated")
        }
        assertEquals("Kamu sedang offline. Periksa koneksimu lalu coba lagi.", SinewStrings.resolve("sinew.error.noInternet", environment = indonesian))
    }

    @Test
    fun indonesianResolvesUnderBothLanguageCodes() = runTest {
        assertEquals(
            SinewStrings.resolve("sinew.error.generic", environment = environment("id")),
            SinewStrings.resolve("sinew.error.generic", environment = environment("in")),
        )
    }

    @Test
    fun pluralsFollowEachLanguageRules() = runTest {
        assertEquals("Too many attempts. Try again in 1 second.", SinewStrings.resolve("sinew.error.rateLimited", mapOf("seconds" to 1L), english))
        assertEquals("Too many attempts. Try again in 30 seconds.", SinewStrings.resolve("sinew.error.rateLimited", mapOf("seconds" to 30L), english))
        assertEquals("Terlalu banyak percobaan. Coba lagi dalam 1 detik.", SinewStrings.resolve("sinew.error.rateLimited", mapOf("seconds" to 1L), indonesian))
    }

    @Test
    fun aKeySinewDoesNotTranslateResolvesToNull() = runTest {
        assertNull(SinewStrings.resolve("orders.error.notCancellable", environment = english))
        assertNull(SinewStrings.resolve("sinew.error.rateLimited", environment = english))   // a plural needs its quantity
    }
}
