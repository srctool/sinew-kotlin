package com.srctool.sinew.exception

import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer.Repository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class LocalizerTest {
    private val indonesian = Localizer { key, args ->
        when (key) {
            "sinew.error.noInternet" -> "Kamu sedang offline. Periksa koneksimu lalu coba lagi."
            "sinew.error.rateLimited" -> "Terlalu banyak percobaan. Coba lagi dalam ${args["seconds"]} detik."
            else -> null
        }
    }

    @Test
    fun serverTextIsShownUnchangedWithTheCode() {
        val e = NotFoundException("ORD", Repository, "GET", ErrorMessage.Server("Pesanan tidak ditemukan."))
        assertEquals("Pesanan tidak ditemukan. (ORD-R-GET-NTF)", e.displayMessage(indonesian))
    }

    @Test
    fun aLocalKeyIsResolvedByTheLocalizer() {
        assertEquals(
            "Kamu sedang offline. Periksa koneksimu lalu coba lagi. (ORD-R-GO-NIC)",
            NoInternetConnectionException("ORD", Repository, "GO").displayMessage(indonesian),
        )
    }

    @Test
    fun argumentsReachTheLocalizer() {
        assertEquals(
            "Terlalu banyak percobaan. Coba lagi dalam 30 detik. (ORD-R-GO-RTL)",
            RateLimitedException("ORD", Repository, "GO", 30.seconds).displayMessage(indonesian),
        )
    }

    @Test
    fun aMissingTranslationFallsBackToEnglishNeverTheRawKey() {
        assertEquals("Your device is out of space. (ORD-R-GO-SF)", StorageFullException("ORD", Repository, "GO").displayMessage(indonesian))
        assertEquals("You're offline. Check your connection and try again. (ORD-R-GO-NIC)", NoInternetConnectionException("ORD", Repository, "GO").displayMessage(null))
    }

    @Test
    fun theFallbackHasItsArgumentsSubstituted() {
        assertEquals(
            "Too many attempts. Try again in 30 seconds. (ORD-R-GO-RTL)",
            RateLimitedException("ORD", Repository, "GO", 30.seconds).displayMessage(null),
        )
    }

    @Test
    fun validationShowsTheCodeOnTheFormMessageOnlyNotOnFieldMessages() {
        val e = ValidationException("ORD", Repository, "SU", fieldErrors = mapOf("email" to listOf("Email is taken.")))
        assertEquals("Please check the highlighted fields. (ORD-R-SU-VAL)", e.displayMessage(null))
        assertEquals(listOf("Email is taken."), e.fieldErrors["email"])
    }
}
