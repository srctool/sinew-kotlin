package com.srctool.sinew.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SinewConfigTest {
    private fun config(
        baseUrl: String = "https://api.example.com/v1",
        connect: Duration = 15.seconds,
        read: Duration = 30.seconds,
        write: Duration = 60.seconds,
        anonymous: Set<String> = emptySet(),
    ) = SinewConfig(
        baseUrl,
        locale = { "en" },
        connectTimeout = connect,
        readTimeout = read,
        writeTimeout = write,
        anonymousPaths = anonymous,
    )

    @Test
    fun theDefaults() {
        val config = config()
        assertEquals(15.seconds, config.connectTimeout)
        assertEquals(30.seconds, config.readTimeout)
        assertEquals(60.seconds, config.writeTimeout)
        assertEquals(false, config.logHttp)
        assertEquals(emptySet(), config.anonymousPaths)
        assertEquals(emptyMap(), config.defaultHeaders())
        assertSame(ErrorBodyReader.Default, config.errorBodyReader)
    }

    @Test
    fun localeAndHeadersAreReadEachTime() {
        var language = "en"
        var build = 1
        val config = SinewConfig("https://api.example.com", { language }, { mapOf("X-Build" to "$build") })
        language = "id"
        build = 2
        assertEquals("id", config.locale())
        assertEquals(mapOf("X-Build" to "2"), config.defaultHeaders())
    }

    @Test
    fun aBlankBaseUrlFails() {
        assertFailsWith<IllegalArgumentException> { config(baseUrl = " ") }
    }

    @Test
    fun aTimeoutThatIsNotPositiveFails() {
        assertFailsWith<IllegalArgumentException> { config(connect = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { config(read = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { config(write = Duration.ZERO) }
    }

    @Test
    fun anAnonymousPathMustStartWithASlash() {
        config(anonymous = setOf("/auth/login"))
        assertFailsWith<IllegalArgumentException> { config(anonymous = setOf("/auth/login", "auth/refresh")) }
    }
}
