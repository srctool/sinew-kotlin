package com.srctool.sinew.l10n

import com.srctool.sinew.exception.Localizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachingLocalizerTest {
    private val formal = Localizer { key, _ -> if (key == "sinew.error.noInternet") "Anda sedang offline." else null }

    /** Waits on real time: resource loading runs on real dispatchers, not the test's virtual clock. */
    private suspend fun eventually(read: () -> String?): String? = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            while (read() == null) delay(10)
            read()
        }
    }

    @Test
    fun theAppsStringsWinOverSinews() = runTest {
        val localizer = CachingLocalizer(formal)
        backgroundScope.launch(Dispatchers.Default) { localizer.load(environment("id")) }
        assertEquals("Anda sedang offline.", localizer.resolve("sinew.error.noInternet", emptyMap()))
        assertEquals("Terjadi kesalahan.", eventually { localizer.resolve("sinew.error.generic", emptyMap()) })
    }

    @Test
    fun aKeyOutsideSinewIsLeftToTheApp() {
        assertNull(CachingLocalizer(appStrings = null).resolve("orders.error.notCancellable", emptyMap()))
    }

    @Test
    fun plainStringsArePreloaded() = runTest {
        val localizer = CachingLocalizer(appStrings = null)
        backgroundScope.launch(Dispatchers.Default) { localizer.load(environment("en")) }
        assertEquals("Your device is out of space.", eventually { localizer.resolve("sinew.error.storageFull", emptyMap()) })
    }

    @Test
    fun aTextNotLoadedYetIsNullThenLoadedOnRequest() = runTest {
        val localizer = CachingLocalizer(appStrings = null)
        val args = mapOf<String, Any>("seconds" to 30L)
        assertNull(localizer.resolve("sinew.error.rateLimited", args))                     // asks for it; the caller shows the fallback this frame
        backgroundScope.launch(Dispatchers.Default) { localizer.load(environment("id")) }
        assertEquals("Terlalu banyak percobaan. Coba lagi dalam 30 detik.", eventually { localizer.resolve("sinew.error.rateLimited", args) })
    }
}
