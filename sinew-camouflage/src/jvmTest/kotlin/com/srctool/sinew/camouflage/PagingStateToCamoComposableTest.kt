package com.srctool.sinew.camouflage

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.srctool.camouflage.core.pagedlist.CamoPagedListState
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.paging.PagingState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The overload renders with Sinew's own strings for the app's language, which on the JVM is the default locale. */
@OptIn(ExperimentalTestApi::class)
class PagingStateToCamoComposableTest {
    private val offline = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "GO")

    private fun assertRenders(language: String, expected: String) {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(language))
        try {
            runComposeUiTest {
                var state = CamoPagedListState<String>()
                setContent { state = PagingState<String>(ViewState.Failed(error = offline)).toCamo() }
                waitUntil(timeoutMillis = 5_000) { state.error?.message == expected }
                assertEquals(expected, state.error?.message)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun rendersEnglish() = assertRenders("en", "You're offline. Check your connection and try again. (${offline.code})")

    @Test
    fun rendersIndonesian() = assertRenders("id", "Kamu sedang offline. Periksa koneksimu lalu coba lagi. (${offline.code})")
}
