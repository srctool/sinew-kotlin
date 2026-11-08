@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.srctool.sinew.devtools

import com.srctool.sinew.network.RefreshCall
import com.srctool.sinew.network.RefreshResult
import com.srctool.sinew.network.SessionTokenSource
import com.srctool.sinew.network.SinewConfig
import com.srctool.sinew.testing.FakeRefreshTokenStore
import com.srctool.sinew.testing.FakeTokenSource
import com.srctool.sinew.testing.mockHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevToolsHttpHooksTest {
    private val mask = Redaction.Default.mask
    private val recorder = NetworkRecorder()
    private val faults = FaultRules()
    private val hosts = HostOverride()
    private val hooks = DevToolsHttpHooks(recorder, faults, hosts)
    private val config = SinewConfig(
        baseUrl = "https://test.local/v1/",
        locale = { "en" },
        defaultHeaders = { mapOf("X-Device-Id" to "device-7") },
    )

    private val records get() = recorder.records.value

    @Test
    fun recordedExchangesAreRedacted() = runTest {
        val http = mockHttp(config, hooks = hooks) {
            on(HttpMethod.Get, "/v1/me").respond(200, """{"name":"ana","refreshToken":"r-1"}""")
        }
        http.client.get("me?token=abc")
        val record = records.single()
        assertEquals("https://test.local/v1/me?token=$mask", record.url)
        assertEquals(listOf(mask), record.requestHeaders[HttpHeaders.Authorization])
        assertEquals(listOf(mask), record.requestHeaders["X-Device-Id"])
        val body = (record.responseBody as RecordedBody.Text).text
        assertFalse("r-1" in body)
        assertTrue("ana" in body)
        assertFalse("token-1" in record.asText() + record.asCurl() + record.toString())
    }

    @Test
    fun aForced401TriggersExactlyOneRefresh() = runTest {
        val tokens = FakeTokenSource()
        val http = mockHttp(config, tokens, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200) }
        faults.add(FaultRule(pathPattern = "/orders", status = 401))
        assertFailsWith<Exception> { http.client.get("orders") }           // the replay gets the fault too
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf(401, 401), records.map { it.status })
        assertEquals(emptyList(), http.requests)
    }

    @Test
    fun aForced503OnTheRefreshKeepsTheSession() = runTest {
        val store = FakeRefreshTokenStore()
        var ended = false
        val refreshHttp = mockHttp(
            SinewConfig(baseUrl = "https://test.local/v1/", locale = { "en" }, anonymousPaths = setOf("/auth/refresh")),
            FakeTokenSource(accessToken = null),
            hooks = hooks,
        ) { on(HttpMethod.Post, "/v1/auth/refresh").respond(200, """{"access":"a-2","refresh":"r-2"}""") }
        val tokens = SessionTokenSource(
            store,
            RefreshCall { _ ->
                refreshHttp.client.post("auth/refresh")
                RefreshResult.Tokens(access = "a-2", refresh = "r-2")
            },
            onSessionEnded = { ended = true },
        )
        tokens.start(accessToken = "a-1", refreshToken = "r-1")
        val http = mockHttp(config, tokens, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(401) }
        faults.add(FaultRule(method = "POST", pathPattern = "/auth/refresh", status = 503))

        assertFailsWith<Exception> { http.client.get("orders") }
        assertEquals("a-1", tokens.accessToken())                         // the session is kept
        assertEquals("r-1", store.token)
        assertFalse(ended)
        assertEquals(listOf(503, 401), records.map { it.status })          // the refresh, then the request that asked for it
        assertEquals(emptyList(), refreshHttp.requests)
    }

    @Test
    fun faultRulesLiveInMemoryOnly() {
        faults.add(FaultRule(pathPattern = "/orders", status = 500))
        assertEquals(1, faults.active.value.size)
        assertEquals(emptyList(), FaultRules().active.value)                // what a restart starts with
    }

    @Test
    fun aDelayRuleSendsAfterIt() = runTest {
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200, "ok") }
        faults.add(FaultRule(pathPattern = "/orders", delay = kotlin.time.Duration.parse("2s")))
        val before = testScheduler.currentTime
        assertEquals("ok", http.client.get("orders").bodyAsText())
        assertEquals(2000, testScheduler.currentTime - before)
    }

    @Test
    fun theHostOverrideSendsToTheChosenHost() = runTest {
        hosts.configure(listOf("https://staging.local/v2"))
        val http = mockHttp(config, hooks = hooks) {
            on(HttpMethod.Get, "/v2/orders").respond(200, "staging")
            on(HttpMethod.Get, "/v1/orders").respond(200, "own")
        }
        hosts.select("https://staging.local/v2")
        assertEquals("staging", http.client.get("orders").bodyAsText())
        assertEquals("https://staging.local/v2/orders", records.first().url)
        assertEquals("Bearer token-1", http.requests.single().headers[HttpHeaders.Authorization])
        hosts.select(null)
        assertEquals("own", http.client.get("orders").bodyAsText())
    }

    @Test
    fun expiringTheTokenRunsTheRealRefresh() = runTest {
        val tokens = FakeTokenSource()
        val http = mockHttp(config, tokens, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(401).respond(200, "ok") }
        hooks.expireAccessToken()
        assertEquals("ok", http.client.get("orders").bodyAsText())
        assertEquals(1, tokens.refreshes)
        assertEquals(listOf("Bearer sinew-devtools-expired", "Bearer token-2"), http.requests.map { it.headers[HttpHeaders.Authorization] })
    }

    // Replay

    @Test
    fun aReplayGoesThroughTheSameClientWithAFreshToken() = runTest {
        val tokens = FakeTokenSource()
        val http = mockHttp(config, tokens, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200, "v1").respond(200, "v2") }
        http.client.get("orders?page=2")
        val original = records.single()
        assertTrue(original.replayable)
        assertTrue(hooks.canReplay(original))
        tokens.rotate()                                                     // the token changed since: the replay gets the new one

        hooks.replay(original)

        val replay = records.first()
        assertTrue(replay.isReplay)
        assertFalse(original.isReplay)
        assertEquals(original.url, replay.url)
        assertEquals("v2", (replay.responseBody as RecordedBody.Text).text)
        val sent = http.requests.last()
        assertEquals("Bearer token-2", sent.headers[HttpHeaders.Authorization])
        assertEquals("device-7", sent.headers["X-Device-Id"])               // the client's own value, not the record's mask
        assertEquals("2", sent.url.parameters["page"])
    }

    @Test
    fun aReplaySendsTheBodyAgain() = runTest {
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Post, "/v1/notes").respond(201) }
        http.client.post("notes") {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"hi"}""")
        }
        hooks.replay(records.single())
        val sent = http.requests.last()
        assertEquals("""{"text":"hi"}""", (sent.body as TextContent).text)
        assertEquals(ContentType.Application.Json, sent.body.contentType?.withoutParameters())
        assertNull(sent.headers[HttpHeaders.ContentType])                     // only the body's, never a copy in the headers
    }

    @Test
    fun faultsAndTheHostOverrideApplyToAReplay() = runTest {
        hosts.configure(listOf("https://staging.local/v2"))
        val http = mockHttp(config, hooks = hooks) {
            on(HttpMethod.Get, "/v1/orders").respond(200)
            on(HttpMethod.Get, "/v2/orders").respond(200, "staging")
        }
        http.client.get("orders")
        val original = records.single()
        hosts.select("https://staging.local/v2")
        hooks.replay(original)
        assertEquals("https://staging.local/v2/orders", records.first().url)
        faults.add(FaultRule(pathPattern = "/orders", status = 503))
        hooks.replay(original)                                              // a failed replay isn't thrown
        assertEquals(503, records.first().status)
        assertTrue(records.first().isReplay)
    }

    @Test
    fun aRecordThatLostASecretCannotBeReplayed() = runTest {
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Post, "/v1/auth/otp").respond(200) }
        http.client.post("auth/otp") {
            contentType(ContentType.Application.Json)
            setBody("""{"otp":"123456"}""")
        }
        val record = records.single()
        assertFalse(record.replayable)
        assertFalse(hooks.canReplay(record))
        assertFailsWith<IllegalArgumentException> { hooks.replay(record) }
    }

    @Test
    fun aClosedClientCannotReplay() = runTest {
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200) }
        http.client.get("orders")
        val record = records.single()
        assertEquals(listOf(http.client), hooks.attachedClients)
        http.client.close()
        assertEquals(emptyList(), hooks.attachedClients)                    // a closed client isn't held
        assertFalse(hooks.canReplay(record))
        assertFailsWith<IllegalStateException> { hooks.replay(record) }
    }

    @Test
    fun aClearedRecordCannotBeReplayed() = runTest {
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Get, "/v1/orders").respond(200) }
        http.client.get("orders")
        val record = records.single()
        recorder.clear()                                                    // "Clear", or a sign-out
        assertFalse(hooks.canReplay(record))
        assertFailsWith<IllegalStateException> { hooks.replay(record) }
    }

    @Test
    fun aStreamedBodyIsRecordedAsBinaryOfUnknownSize() = runTest {
        val http = mockHttp(config, hooks = hooks) { on(HttpMethod.Post, "/v1/upload").respond(204) }
        http.client.post("upload") { setBody(ByteReadChannel(byteArrayOf(1, 2, 3))) }
        val record = records.single()
        assertEquals(RecordedBody.Binary("application/octet-stream", UNKNOWN_SIZE), record.requestBody)
        assertFalse(record.replayable)
        assertTrue("unknown size" in record.asText(), record.asText())
    }

    @Test
    fun aRecordFromACacheOrAnotherRecorderHasNoTarget() {
        val stray = NetworkRecorder().recordCacheServe("k", "memory")
        assertFalse(hooks.canReplay(stray))
    }
}

/** Hook-level tests don't share DevTools' globals; this file only checks the global wiring. */
class DevToolsGlobalsTest {
    @Test
    fun theGlobalHooksRecordIntoTheGlobalRecorder() = runTest {
        DevTools.network.clear()
        val http = TestScope(testScheduler).mockHttp(hooks = DevTools.httpHooks) {
            on(HttpMethod.Get, "/v1/orders").respond(200)
        }
        http.client.get("orders")
        val record = DevTools.network.records.value.single()
        assertTrue(DevTools.canReplay(record))
        DevTools.replay(record)
        assertTrue(DevTools.network.records.value.first().isReplay)
        DevTools.clearUserData()
    }
}
