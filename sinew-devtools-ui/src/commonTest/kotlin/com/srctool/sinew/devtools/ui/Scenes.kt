package com.srctool.sinew.devtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.srctool.sinew.devtools.DevTools
import com.srctool.sinew.devtools.ExceptionSource
import com.srctool.sinew.devtools.FaultRule
import com.srctool.sinew.devtools.NetworkExchange
import com.srctool.sinew.devtools.NetworkRecord
import com.srctool.sinew.devtools.Redaction
import com.srctool.sinew.testing.FakeSecureStore
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The pages the screenshot tests capture, the same on desktop and Android. Each scene seeds devtools' state with
 * fixed values, so the images don't change between runs.
 */
internal object Scenes {
    val names: List<String> = listOf(
        "home",
        "network-list",
        "network-detail",
        "network-blocked",
        "exceptions",
        "secure-store",
        "faults",
        "overlay",
        "uncaught",
    )

    private val at = Instant.parse("2026-11-08T10:00:00Z")
    private val secure = FakeSecureStore("session")
    private lateinit var get: NetworkRecord
    private lateinit var otp: NetworkRecord

    /** Resets devtools and seeds what [name] shows. */
    suspend fun prepare(name: String) {
        DevTools.close()
        DevTools.network.clear()
        DevTools.exceptions.clear()
        DevTools.faults.clear()
        DevTools.network.redaction = Redaction.Default
        otp = DevTools.network.record(
            NetworkExchange(
                method = "POST",
                url = "https://api.shop.test/v1/auth/otp",
                requestHeaders = mapOf("Content-Type" to listOf("application/json")),
                requestBody = """{"phone":"0812","otp":"123456"}""".encodeToByteArray(),
                status = 200,
                responseHeaders = mapOf("Content-Type" to listOf("application/json")),
                responseBody = """{"refreshToken":"r-1","name":"Ana"}""".encodeToByteArray(),
                startedAt = at,
                duration = 310.milliseconds,
            ),
        )
        get = DevTools.network.record(
            NetworkExchange(
                method = "GET",
                url = "https://api.shop.test/v1/orders?page=2",
                requestHeaders = mapOf("Authorization" to listOf("Bearer token-1"), "Accept" to listOf("application/json")),
                status = 200,
                responseHeaders = mapOf("Content-Type" to listOf("application/json")),
                responseBody = """{"items":[{"id":7,"status":"shipped"}],"page":2}""".encodeToByteArray(),
                startedAt = at,
                duration = 245.milliseconds,
            ),
        )
        DevTools.exceptions.record(IllegalStateException("Order 7 has no address"))
        when (name) {
            "secure-store" -> {
                secure.clearUserData()
                secure.write("refresh_token", "eyJhbGciOiJIUzI1NiJ9.payload.signature-9f2c")
                secure.write("pin", "1234")
            }
            "faults", "overlay" -> DevTools.faults.add(FaultRule(pathPattern = "/orders", status = 503))
            "uncaught" -> DevTools.exceptions.record(IllegalStateException("Checkout crashed"), ExceptionSource.Uncaught)
        }
    }

    @Composable
    fun Render(name: String) {
        CompositionLocalProvider(LocalDevToolsDark provides false) { Scene(name) }
    }

    @Composable
    private fun Scene(name: String) {
        val product = DevToolSection("Product", listOf(DevToolEntry.Page("Remote copy", "Strings from the backend") { Text("copy") }))
        val sections = listOf(sinewSection(listOf(secure), changelog = "1.4.0", hasHosts = true), product)
        when (name) {
            "home" -> Dashboard(sections, onClose = {})
            "network-list" -> Page("Network Monitor") { NetworkMonitorPage() }
            "network-detail" -> Page("GET /v1/orders") { NetworkDetailPage(get) }
            "network-blocked" -> Page("POST /v1/auth/otp") { NetworkDetailPage(otp) }
            "exceptions" -> Page("Exception Log") { ExceptionLogPage() }
            "secure-store" -> Page("Secure Store") { SecureStorePage(secure) }
            "faults" -> Page("Fault Injection") { FaultInjectionPage() }
            "overlay", "uncaught" -> SinewDevTools(ribbon = BuildRibbon("1.4.0 (212) · staging"), showUncaughtErrors = true) { App() }
        }
    }

    @Composable
    private fun Page(title: String, content: @Composable () -> Unit) {
        Dashboard(emptyList(), onClose = {}, initial = listOf(Screen(title, content = content)))
    }

    @Composable
    private fun App() {
        Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA)), contentAlignment = Alignment.Center) { Text("The app") }
    }
}

/** Where a screenshot's reference lives, and whether to record it or compare against it. */
internal class ScreenshotTarget(val path: String, val record: Boolean)

/** [name]'s reference for [osName] (the JVM's `os.name`) and [platform] (`desktop`, `android`). */
internal fun screenshotTarget(platform: String, name: String, dir: String, mode: String, osName: String, exists: (String) -> Boolean): ScreenshotTarget {
    val os = when {
        osName.startsWith("Mac") -> "macos"
        osName.startsWith("Windows") -> "windows"
        else -> "linux"
    }
    val path = "$dir/$os/$platform/$name.png"
    return ScreenshotTarget(path, record = mode == "record" || !exists(path))
}
