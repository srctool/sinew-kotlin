package com.srctool.sinew.devtools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import com.srctool.sinew.devtools.DevTools
import com.srctool.sinew.devtools.ExceptionSource
import com.srctool.sinew.devtools.NetworkExchange
import com.srctool.sinew.devtools.Redaction
import com.srctool.sinew.network.SinewConfig
import com.srctool.sinew.network.SinewHttp
import com.srctool.sinew.testing.FakeKeyValueStore
import com.srctool.sinew.testing.FakeSecureStore
import com.srctool.sinew.testing.FakeTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class SinewDevToolsTest {
    private val copied = mutableListOf<String>()
    private val clipboard = DevToolsClipboard { copied += it }

    @BeforeTest
    @AfterTest
    fun reset() {
        DevTools.close()
        DevTools.network.clear()
        DevTools.exceptions.clear()
        DevTools.faults.clear()
        DevTools.network.redaction = Redaction.Default
    }

    private fun ComposeUiTest.show(
        launcher: DevToolsLauncher = FloatingLauncher(),
        sections: List<DevToolSection> = emptyList(),
        stores: List<com.srctool.sinew.storage.InspectableStore> = emptyList(),
        hosts: List<String> = emptyList(),
        changelog: String? = null,
        ribbon: BuildRibbon? = null,
        showUncaughtErrors: Boolean = false,
        preferences: com.srctool.sinew.storage.KeyValueStore? = null,
        redaction: Redaction = Redaction.Default,
        app: @Composable () -> Unit = { Text("The app") },
    ) {
        setContent {
            CompositionLocalProvider(LocalDevToolsClipboard provides clipboard) {
                SinewDevTools(
                    sections = sections,
                    ribbon = ribbon,
                    launcher = launcher,
                    redaction = redaction,
                    stores = stores,
                    changelog = changelog,
                    hosts = hosts,
                    showUncaughtErrors = showUncaughtErrors,
                    preferences = preferences,
                    content = app,
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openPage(name: String) {
        if (!DevTools.isOpen.value) DevTools.open()
        waitForIdle()
        onNodeWithTag("entry:$name").performClick()
        waitForIdle()
    }

    /**
     * Waits in real time for a request the UI started. `waitUntil` blocks the browser's only thread on wasm, so the
     * mock engine's answer could never arrive; this settles the UI, then yields, in turn.
     */
    private suspend fun ComposeUiTest.awaitReal(condition: () -> Boolean) {
        repeat(400) {
            waitForIdle()                                                   // runs the UI's own coroutines
            if (condition()) return
            withContext(Dispatchers.Default) { delay(25) }                  // and lets the mock engine answer
        }
        assertTrue(condition(), "still not true after 10 s")
    }

    private fun ComposeUiTest.exists(tag: String): Boolean = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.textShown(text: String): Boolean =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    // The dashboard and its sections

    @Test
    fun anAppSectionAppearsAfterTheSinewSection() = runComposeUiTest {
        val product = DevToolSection("Product", listOf(DevToolEntry.Page("Remote copy") { Text("copy") }))
        show(sections = listOf(product))
        DevTools.open()
        waitForIdle()
        val sinew = onNodeWithTag("section:Sinew").fetchSemanticsNode().boundsInRoot.top
        val app = onNodeWithTag("section:Product").fetchSemanticsNode().boundsInRoot.top
        assertTrue(sinew < app, "Sinew at $sinew, the app's section at $app")
        onNodeWithTag("entry:Remote copy").performClick()
        onNodeWithText("copy").assertExists()
    }

    @Test
    fun theFloatingButtonOpensTheDashboardAndHidesWhileItsOpen() = runComposeUiTest {
        show()
        onNodeWithText("The app").assertExists()
        onNodeWithTag(FLOATING_TAG).performClick()
        waitForIdle()
        assertTrue(DevTools.isOpen.value)
        onNodeWithTag(DASHBOARD_TAG).assertExists()
        assertTrue(!exists(FLOATING_TAG))
        onNodeWithTag(BACK_TAG).performClick()                               // "Close" on the first screen
        waitForIdle()
        assertTrue(!DevTools.isOpen.value)
        onNodeWithTag(FLOATING_TAG).assertExists()
    }

    @Test
    fun aDragNeverOpensTheDashboardAndItsPositionIsRemembered() = runComposeUiTest {
        val preferences = FakeKeyValueStore()
        show(preferences = preferences)
        val before = onNodeWithTag(FLOATING_TAG).fetchSemanticsNode().boundsInRoot
        onNodeWithTag(FLOATING_TAG).performTouchInput { swipe(center, center + Offset(-120f, -200f), durationMillis = 300) }
        waitForIdle()
        assertTrue(!DevTools.isOpen.value)
        val after = onNodeWithTag(FLOATING_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(after.top < before.top && after.left < before.left, "moved from $before to $after")
        waitUntil(timeoutMillis = 5_000) { preferences.snapshot.containsKey(BUTTON_X) }
        assertTrue((preferences.getDouble(BUTTON_X) ?: 0.0) < 0)
        assertTrue((preferences.getDouble(BUTTON_Y) ?: 0.0) < 0)
    }

    @Test
    fun theMenuLauncherHasNoButtonAndItsRowOpensTheSameDashboard() = runComposeUiTest {
        show(launcher = MenuLauncher, app = { Column { Text("Settings"); DevToolsMenuItem() } })
        assertTrue(!exists(FLOATING_TAG))
        onNodeWithTag(MENU_ITEM_TAG).performClick()
        waitForIdle()
        onNodeWithTag(HOME_TAG).assertExists()
        onNodeWithTag("section:Sinew").assertExists()
    }

    @Test
    fun theCustomLauncherOpensOnlyWhenTheAppAsks() = runComposeUiTest {
        show(launcher = CustomLauncher)
        assertTrue(!exists(FLOATING_TAG))
        assertTrue(!exists(DASHBOARD_TAG))
        DevTools.open()
        waitForIdle()
        onNodeWithTag("section:Sinew").assertExists()
        DevTools.close()
        waitForIdle()
        assertTrue(!exists(DASHBOARD_TAG))
    }

    @Test
    fun theBackButtonWalksTheDashboardsOwnStack() = runComposeUiTest {
        show()
        openPage("Network Monitor")
        onNodeWithText("No requests yet").assertExists()
        onNodeWithTag(BACK_TAG).performClick()
        waitForIdle()
        onNodeWithTag(HOME_TAG).assertExists()
        assertTrue(DevTools.isOpen.value)
    }

    @Test
    fun theOptionalPagesAppearOnlyWhenGiven() = runComposeUiTest {
        show(changelog = "1.4.0: faster lists", hosts = listOf("https://staging.local/v1"))
        DevTools.open()
        waitForIdle()
        onNodeWithTag("entry:API Host").assertExists()
        openPage("Changelog")
        onNodeWithText("1.4.0: faster lists").assertExists()
    }

    @Test
    fun withoutHostsOrAChangelogThoseEntriesAreAbsent() = runComposeUiTest {
        show()
        DevTools.open()
        waitForIdle()
        assertTrue(!exists("entry:API Host"))
        assertTrue(!exists("entry:Changelog"))
    }

    // App entries

    @Test
    fun anActionWithAConfirmationAsksFirst() = runComposeUiTest {
        var ran = 0
        val section = DevToolSection("Product", listOf(DevToolEntry.Action("Reset onboarding", confirm = "Show it again?") { ran++ }))
        show(sections = listOf(section))
        DevTools.open()
        waitForIdle()
        onNodeWithTag("entry:Reset onboarding").performClick()
        onNodeWithText("Show it again?").assertExists()
        onNodeWithTag(DISMISS_TAG).performClick()
        waitForIdle()
        assertEquals(0, ran)
        onNodeWithTag("entry:Reset onboarding").performClick()
        onNodeWithTag(CONFIRM_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { ran == 1 }
    }

    @Test
    fun aToggleFlipsAndRedraws() = runComposeUiTest {
        var on = false
        val section = DevToolSection("Product", listOf(DevToolEntry.Toggle("New checkout", value = { on }, onChanged = { on = it })))
        show(sections = listOf(section))
        DevTools.open()
        waitForIdle()
        onNodeWithTag("toggle:New checkout").performClick()
        waitForIdle()
        assertTrue(on)
        onNodeWithTag("toggle:New checkout").performClick()
        waitForIdle()
        assertTrue(!on)
    }

    @Test
    fun aGroupOpensItsChildren() = runComposeUiTest {
        val group = DevToolEntry.Group("Playgrounds", children = listOf(DevToolEntry.Page("Forms") { Text("form playground") }))
        show(sections = listOf(DevToolSection("Product", listOf(group))))
        openPage("Playgrounds")
        onNodeWithTag("entry:Forms").performClick()
        onNodeWithText("form playground").assertExists()
    }

    // Network Monitor

    private fun recordGet(url: String = "https://api.test/v1/orders?token=abc") = DevTools.network.record(
        NetworkExchange(
            method = "GET",
            url = url,
            requestHeaders = mapOf("Authorization" to listOf("Bearer secret-token")),
            status = 200,
            startedAt = Instant.parse("2026-11-08T10:00:00Z"),
        ),
    )

    @Test
    fun theNetworkMonitorListsRecordsAndCopiesThemRedacted() = runComposeUiTest {
        show()
        val record = recordGet()
        openPage("Network Monitor")
        onNodeWithTag("network-record:${record.id}").performClick()
        waitForIdle()
        onNodeWithTag("network-copy").performClick()
        onNodeWithTag("network-curl").performClick()
        assertEquals(listOf(record.asText(), record.asCurl()), copied)
        assertTrue(copied.none { "secret-token" in it || "abc" in it })
        assertTrue(!textShown("secret-token"))
    }

    @Test
    fun clearEmptiesTheNetworkMonitor() = runComposeUiTest {
        show()
        recordGet()
        openPage("Network Monitor")
        onNodeWithTag("network-clear").performClick()
        waitForIdle()
        onNodeWithText("No requests yet").assertExists()
    }

    private fun mockClient(): HttpClient = SinewHttp.client(
        SinewConfig(baseUrl = "https://api.test/v1/", locale = { "en" }),
        FakeTokenSource(),
        MockEngine,
        hooks = DevTools.httpHooks,
    ) {
        engine { addHandler { respond("{}", HttpStatusCode.OK) } }
    }

    @Test
    fun aGetReplaysOnTap() = runComposeUiTest {
        val client = mockClient()
        show()
        client.get("orders")
        val record = DevTools.network.records.value.single()
        openPage("Network Monitor")
        onNodeWithTag("network-record:${record.id}").performClick()
        waitForIdle()
        onNodeWithTag("network-replay").assertIsEnabled().performClick()
        awaitReal { DevTools.network.records.value.first().isReplay }
        client.close()
    }

    @Test
    fun anyOtherMethodAsksBeforeItReplays() = runComposeUiTest {
        val client = mockClient()
        show()
        client.post("orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"item":7}""")
        }
        val record = DevTools.network.records.value.single()
        openPage("Network Monitor")
        onNodeWithTag("network-record:${record.id}").performClick()
        waitForIdle()
        onNodeWithTag("network-replay").performClick()
        onNodeWithText("It may repeat what it did", substring = true).assertExists()
        onNodeWithTag(DISMISS_TAG).performClick()
        waitForIdle()
        assertEquals(1, DevTools.network.records.value.size)
        onNodeWithTag("network-replay").performClick()
        onNodeWithTag(CONFIRM_TAG).performClick()
        awaitReal { DevTools.network.records.value.size == 2 }
        assertTrue(DevTools.network.records.value.first().isReplay)
        client.close()
    }

    @Test
    fun aRecordThatLostASecretCannotBeReplayedAndSaysWhy() = runComposeUiTest {
        val client = mockClient()
        show()
        client.post("auth/otp") {
            contentType(ContentType.Application.Json)
            setBody("""{"otp":"123456"}""")
        }
        val record = DevTools.network.records.value.single()
        openPage("Network Monitor")
        onNodeWithTag("network-record:${record.id}").performClick()
        waitForIdle()
        onNodeWithTag("network-replay").assertIsNotEnabled()
        onNodeWithTag("network-replay-reason").assertExists()
        onNodeWithText("a secret in it was hidden", substring = true).assertExists()
        client.close()
    }

    @Test
    fun theAppsRedactionReachesTheRecorders() = runComposeUiTest {
        show(redaction = Redaction.Default + { fieldParts += "cardNumber" })
        val record = DevTools.network.record(
            NetworkExchange(method = "GET", url = "https://api.test/pay?card_number=4111", startedAt = Instant.parse("2026-11-08T10:00:00Z")),
        )
        assertTrue("4111" !in record.url)
    }

    // Exception Log

    @Test
    fun theExceptionLogListsRowsAndCopiesThem() = runComposeUiTest {
        show()
        DevTools.exceptions.record(IllegalStateException("boom"))
        val row = DevTools.exceptions.records.value.single()
        openPage("Exception Log")
        onNodeWithTag("exception-row:${row.id}").performClick()
        waitForIdle()
        onNodeWithTag("exception-copy").performClick()
        assertEquals(listOf(row.asText()), copied)
    }

    @Test
    fun anUncaughtErrorPopsUpACardOnlyWhenAskedTo() = runComposeUiTest {
        show(showUncaughtErrors = true)
        DevTools.exceptions.record(IllegalStateException("crash"), ExceptionSource.Uncaught)
        waitForIdle()
        onNodeWithTag(UNCAUGHT_TAG).assertExists()
        onNodeWithText("crash", substring = true).assertExists()
        onNodeWithTag("uncaught-dismiss").performClick()
        waitForIdle()
        assertTrue(!exists(UNCAUGHT_TAG))
    }

    @Test
    fun withoutTheOptionNoCardPopsUp() = runComposeUiTest {
        show(showUncaughtErrors = false)
        DevTools.exceptions.record(IllegalStateException("crash"), ExceptionSource.Uncaught)
        waitForIdle()
        assertTrue(!exists(UNCAUGHT_TAG))
    }

    // Stores

    @Test
    fun theSecureStorePageShowsOnlyMaskedValues() = runComposeUiTest {
        val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig-9f2c"
        val store = FakeSecureStore("session").apply {
            write("refresh_token", token)
            write("pin", "1234")
        }
        show(stores = listOf(store))
        openPage("Secure Store")
        waitUntil(timeoutMillis = 5_000) { exists("secure-row:pin") }
        onNodeWithText(Redaction.maskSecret(token)).assertExists()
        onNodeWithText("•••• (4 chars)").assertExists()
        assertTrue(!textShown(token) && !textShown("payload") && !textShown("1234"))
        assertTrue(!exists("network-copy") && !exists("exception-copy"))  // nothing on the page copies
        assertTrue(copied.isEmpty())
    }

    @Test
    fun clearingTheSecureStoreAsksFirst() = runComposeUiTest {
        val store = FakeSecureStore("session").apply { write("refresh_token", "r") }
        show(stores = listOf(store))
        openPage("Secure Store")
        waitUntil(timeoutMillis = 5_000) { exists("secure-row:refresh_token") }
        onNodeWithTag("secure-clear").performClick()
        onNodeWithTag(DISMISS_TAG).performClick()
        waitForIdle()
        assertEquals("r", store.read("refresh_token"))
        onNodeWithTag("secure-clear").performClick()
        onNodeWithTag(CONFIRM_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { textShown("session is empty") }
        assertNull(store.read("refresh_token"))
    }

    @Test
    fun theKeyValueStoreEditsByTypeAndDeletesAfterAsking() = runComposeUiTest {
        val store = FakeKeyValueStore("prefs").apply {
            setInt("launches", 3)
            setString("theme", "dark")
        }
        show(stores = listOf(store))
        openPage("Key-Value Store")
        waitUntil(timeoutMillis = 5_000) { exists("kv-row:launches") }
        onNodeWithTag("kv-row:launches").performClick()
        onNodeWithTag("kv-edit-field").performTextReplacement("many")
        onNodeWithTag(CONFIRM_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { textShown("Not a whole number") }
        onNodeWithTag("kv-edit-field").performTextReplacement("4")
        onNodeWithTag(CONFIRM_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { store.snapshot["launches"] == 4 }
        waitUntil(timeoutMillis = 5_000) { !exists("kv-edit-field") }        // the edit dialog has closed

        onNodeWithTag("kv-delete:theme").performClick()
        onNodeWithTag(DISMISS_TAG).performClick()
        waitForIdle()
        assertEquals("dark", store.getString("theme"))
        onNodeWithTag("kv-delete:theme").performClick()
        onNodeWithTag(CONFIRM_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { !store.snapshot.containsKey("theme") }
    }

    // Fault Injection and API Host

    @Test
    fun aFaultRuleIsAddedFromThePageAndTheRibbonSaysSo() = runComposeUiTest {
        show(ribbon = BuildRibbon("1.4.0 · staging"))
        onNodeWithTag(RIBBON_TAG).assertExists()
        onNodeWithText("1.4.0 · staging").assertExists()
        openPage("Fault Injection")
        onNodeWithTag("fault-path").performTextInput("/orders")
        onNodeWithTag("fault-add").performClick()
        waitForIdle()
        assertEquals(503, DevTools.faults.active.value.single().status)
        onNodeWithText("1.4.0 · staging · faults on").assertExists()
        onNodeWithTag("fault-remove:/orders").performClick()
        waitForIdle()
        assertTrue(DevTools.faults.active.value.isEmpty())
    }

    @Test
    fun anInvalidRuleSaysWhy() = runComposeUiTest {
        show()
        openPage("Fault Injection")
        onNodeWithTag("fault-add").performClick()                          // no path
        waitForIdle()
        onNodeWithTag("fault-error").assertExists()
        assertTrue(DevTools.faults.active.value.isEmpty())
    }

    @Test
    fun aHostIsChosenFromThePage() = runComposeUiTest {
        val staging = "https://staging.local/v1"
        show(hosts = listOf(staging))
        waitUntil(timeoutMillis = 5_000) { DevTools.hosts.hosts.value == listOf(staging) }
        openPage("API Host")
        onNodeWithTag("host:$staging").performClick()
        waitUntil(timeoutMillis = 5_000) { DevTools.hosts.current.value == staging }
        onNodeWithText("staging.local").assertExists()                      // in the ribbon
        onNodeWithTag("host:own").performClick()
        waitUntil(timeoutMillis = 5_000) { DevTools.hosts.current.value == null }
    }

    @Test
    fun expiringTheTokenAsksFirst() = runComposeUiTest {
        show()
        DevTools.open()
        waitForIdle()
        onNodeWithTag("entry:Expire Access Token").performClick()
        onNodeWithText("the real refresh runs", substring = true).assertExists()
        onNodeWithTag(DISMISS_TAG).performClick()
    }

}
