package com.srctool.sinew.devtools

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class NetworkRecorderTest {
    private val at = Instant.parse("2026-11-07T10:00:00Z")
    private val mask = Redaction.Default.mask

    private fun exchange(
        method: String = "GET",
        url: String = "https://api.test/v1/orders",
        requestHeaders: Map<String, List<String>> = emptyMap(),
        requestBody: String? = null,
        status: Int? = 200,
        responseHeaders: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json")),
        responseBody: String? = """{"items":[]}""",
        error: Throwable? = null,
        clientHeaders: Set<String> = emptySet(),
        isReplay: Boolean = false,
    ) = NetworkExchange(
        method = method,
        url = url,
        requestHeaders = requestHeaders,
        requestBody = requestBody?.encodeToByteArray(),
        status = status,
        responseHeaders = responseHeaders,
        responseBody = responseBody?.encodeToByteArray(),
        startedAt = at,
        duration = 245.milliseconds,
        error = error,
        clientHeaders = clientHeaders,
        isReplay = isReplay,
    )

    @AfterTest
    fun reset() = DevTools.network.clear()

    @Test
    fun theCapacityDropsTheOldestRecord() {
        val recorder = NetworkRecorder(capacity = 3)
        repeat(5) { recorder.record(exchange(url = "https://api.test/$it")) }
        assertEquals(listOf("https://api.test/4", "https://api.test/3", "https://api.test/2"), recorder.records.value.map { it.url })
    }

    @Test
    fun nothingUnredactedIsEverStored() {
        val secrets = listOf("Bearer-abc123", "device-77", "qs-token-1", "hunter2", "otp-445566", "cookie-s3cr3t", "refresh-r1")
        val recorder = NetworkRecorder()
        recorder.record(
            exchange(
                method = "POST",
                url = "https://api.test/v1/login?token=qs-token-1&lang=en",
                requestHeaders = mapOf(
                    "Authorization" to listOf("Bearer Bearer-abc123"),
                    "X-Device-Id" to listOf("device-77"),
                    "Content-Type" to listOf("application/json"),
                ),
                requestBody = """{"user":{"name":"ana","credentials":[{"password":"hunter2"}]},"otp":"otp-445566"}""",
                responseHeaders = mapOf("Set-Cookie" to listOf("session=cookie-s3cr3t"), "Content-Type" to listOf("application/json")),
                responseBody = """{"data":{"refreshToken":"refresh-r1","name":"ana"}}""",
                clientHeaders = setOf("X-Device-Id"),
            ),
        )
        val record = recorder.records.value.single()
        for (stored in listOf(record.toString(), record.asText(), record.asCurl())) {
            for (secret in secrets) assertFalse(secret in stored, "$secret leaked into: $stored")
        }
        assertTrue("ana" in record.asText())                     // the rest is kept
    }

    @Test
    fun theResponseBodyIsRedactedByItsOwnContentType() {
        val record = NetworkRecorder().record(
            exchange(method = "POST", responseHeaders = form, responseBody = "access_token=abc&expires_in=3600"),
        )
        assertEquals("access_token=$mask&expires_in=3600", (record.responseBody as RecordedBody.Text).text)
    }

    @Test
    fun aRequestThatOnlyLostTheClientsOwnHeadersCanBeReplayed() {
        val recorder = NetworkRecorder()
        val record = recorder.record(
            exchange(
                requestHeaders = mapOf("Authorization" to listOf("Bearer t"), "X-Device-Id" to listOf("d"), "Accept" to listOf("*/*")),
                clientHeaders = setOf("X-Device-Id"),
            ),
        )
        assertTrue(record.replayable)
        assertEquals(listOf(mask), record.requestHeaders["Authorization"])
        assertEquals(listOf(mask), record.requestHeaders["X-Device-Id"])
        assertTrue(recorder.record(exchange(method = "POST", requestBody = """{"name":"ana"}""", requestHeaders = json)).replayable)
    }

    @Test
    fun aRequestThatLostASecretOfItsOwnCannotBeReplayed() {
        val recorder = NetworkRecorder()
        assertFalse(recorder.record(exchange(url = "https://api.test/v1/me?token=abc")).replayable, "query")
        assertFalse(recorder.record(exchange(requestHeaders = mapOf("X-Api-Key" to listOf("k")))).replayable, "own header")
        assertFalse(recorder.record(exchange(requestHeaders = mapOf("Cookie" to listOf("a=1")))).replayable, "cookie")
        assertFalse(recorder.record(exchange(method = "POST", requestBody = """{"otp":"1"}""", requestHeaders = json)).replayable, "body")
        assertFalse(
            recorder.record(exchange(method = "POST", requestBody = "password=p", requestHeaders = form)).replayable,
            "form",
        )
    }

    @Test
    fun aCutOrBinaryRequestBodyCannotBeReplayed() {
        val small = NetworkRecorder(redaction = Redaction.Default + { maxBodyBytes = 4 })
        assertFalse(small.record(exchange(method = "POST", requestBody = """{"name":"ana"}""", requestHeaders = json)).replayable)
        val recorder = NetworkRecorder()
        val upload = recorder.record(
            NetworkExchange(
                method = "PUT",
                url = "https://api.test/v1/avatar",
                requestHeaders = mapOf("Content-Type" to listOf("image/png")),
                requestBody = byteArrayOf(1, 2, 3),
                startedAt = at,
            ),
        )
        assertEquals(RecordedBody.Binary("image/png", 3), upload.requestBody)
        assertFalse(upload.replayable)
    }

    @Test
    fun aRecordKeepsTheExchangesFacts() {
        val error = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "list")
        val record = NetworkRecorder().record(exchange(method = "get", status = null, responseBody = null, error = error, isReplay = true))
        assertEquals("GET", record.method)
        assertEquals(null, record.status)
        assertEquals("NoInternetConnectionException", record.errorType)
        assertEquals(error.code, record.errorCode)
        assertEquals(at, record.startedAt)
        assertEquals(245.milliseconds, record.duration)
        assertTrue(record.isReplay)
        assertFalse(record.fromCache)
        assertEquals(RecordedBody.None, record.responseBody)
    }

    @Test
    fun aPlainErrorKeepsItsType() {
        val record = NetworkRecorder().record(exchange(error = IllegalStateException("x")))
        assertEquals("IllegalStateException", record.errorType)
        assertEquals(null, record.errorCode)
    }

    @Test
    fun aCacheServeIsRedactedAndNeverReplayable() {
        val recorder = NetworkRecorder()
        val record = recorder.recordCacheServe(
            key = "https://api.test/v1/me?token=abc",
            source = "disk",
            body = """{"name":"ana","pin":"1234"}""".encodeToByteArray(),
            contentType = "application/json",
            at = at,
        )
        assertTrue(record.fromCache)
        assertEquals("disk", record.cacheSource)
        assertFalse(record.replayable)
        assertEquals("CACHE", record.method)
        assertEquals("https://api.test/v1/me?token=$mask", record.url)
        val body = (record.responseBody as RecordedBody.Text).text
        assertFalse("1234" in body)
        assertTrue("ana" in body)
        assertEquals(listOf(record), recorder.records.value)
    }

    @Test
    fun clearEmptiesIt() {
        val recorder = NetworkRecorder()
        recorder.record(exchange())
        recorder.clear()
        assertEquals(emptyList(), recorder.records.value)
    }

    @Test
    fun clearUserDataEmptiesTheNetworkRecords() {
        DevTools.network.record(exchange())
        DevTools.clearUserData()
        assertEquals(emptyList(), DevTools.network.records.value)
    }

    @Test
    fun theCapacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { NetworkRecorder(capacity = 0) }
    }

    // Copy and Copy as cURL

    @Test
    fun asTextShowsTheRecordRedacted() {
        val record = NetworkRecorder().record(
            exchange(
                method = "POST",
                requestHeaders = mapOf("Authorization" to listOf("Bearer t"), "Content-Type" to listOf("application/json")),
                requestBody = """{"name":"ana"}""",
                status = 201,
                responseBody = """{"id":7}""",
                isReplay = true,
            ),
        )
        assertEquals(
            """
            POST https://api.test/v1/orders
            → 201 · 245 ms · replay

            Request headers
            Authorization: $mask
            Content-Type: application/json

            Request body
            {"name":"ana"}

            Response headers
            Content-Type: application/json

            Response body
            {"id":7}
            """.trimIndent(),
            record.asText(),
        )
    }

    @Test
    fun asTextNamesTheErrorACutBodyAndABinaryBody() {
        val recorder = NetworkRecorder(redaction = Redaction.Default + { maxBodyBytes = 4 })
        val error = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "list")
        val text = recorder.record(
            NetworkExchange(
                method = "PUT",
                url = "https://api.test/a",
                requestHeaders = mapOf("Content-Type" to listOf("image/png")),
                requestBody = ByteArray(2048),
                responseHeaders = mapOf("Content-Type" to listOf("text/plain")),
                responseBody = "abcdefgh".encodeToByteArray(),
                startedAt = at,
                error = error,
            ),
        ).asText()
        assertTrue("→ no response" in text, text)
        assertTrue("Error: NoInternetConnectionException ${error.code}" in text, text)
        assertTrue("image/png, 2 KB (not recorded)" in text, text)
        assertTrue("abcd\n(cut at 4 B of 8 B)" in text, text)
        assertTrue("from cache (memory)" in NetworkRecorder().recordCacheServe("k", "memory", at = at).asText())
    }

    @Test
    fun asCurlIsARedactedCommand() {
        val record = NetworkRecorder().record(
            exchange(
                method = "POST",
                url = "https://api.test/v1/notes?token=abc",
                requestHeaders = mapOf("Authorization" to listOf("Bearer t"), "Content-Type" to listOf("application/json")),
                requestBody = """{"text":"it's"}""",
            ),
        )
        assertEquals(
            """
            curl -X POST 'https://api.test/v1/notes?token=$mask' \
              -H 'Authorization: $mask' \
              -H 'Content-Type: application/json' \
              --data-raw '{"text":"it'\''s"}'
            """.trimIndent(),
            record.asCurl(),
        )
    }

    @Test
    fun asCurlLeavesOutWhatItCannotSend() {
        val get = NetworkRecorder().record(exchange())
        assertEquals("curl 'https://api.test/v1/orders'", get.asCurl())
        val upload = NetworkRecorder().record(
            NetworkExchange(
                method = "PUT",
                url = "https://api.test/a",
                requestHeaders = mapOf("Content-Type" to listOf("image/png")),
                requestBody = ByteArray(3),
                startedAt = at,
            ),
        )
        assertEquals(
            "curl -X PUT 'https://api.test/a' \\\n  -H 'Content-Type: image/png'\n# body not included: image/png, 3 B",
            upload.asCurl(),
        )
        val cut = NetworkRecorder(redaction = Redaction.Default + { maxBodyBytes = 4 })
            .record(exchange(method = "POST", requestBody = "abcdefgh", requestHeaders = mapOf("Content-Type" to listOf("text/plain"))))
        assertTrue(cut.asCurl().endsWith("--data-raw 'abcd'\n# body cut at 4 B of 8 B"), cut.asCurl())
    }

    @Test
    fun sizesReadNaturally() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1 KB", formatSize(1024))
        assertEquals("12 KB", formatSize(12 * 1024))
        assertEquals("2 KB", formatSize(1800))                  // rounded, not cut
        assertEquals("1 MB", formatSize(1024 * 1024))
        assertEquals("1.5 MB", formatSize(1024 * 1024 * 3 / 2))
        assertEquals("20 MB", formatSize(20L * 1024 * 1024))
    }

    private companion object {
        val json = mapOf("Content-Type" to listOf("application/json"))
        val form = mapOf("Content-Type" to listOf("application/x-www-form-urlencoded"))
    }
}
