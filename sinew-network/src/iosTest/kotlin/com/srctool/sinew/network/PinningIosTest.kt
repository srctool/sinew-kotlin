package com.srctool.sinew.network

import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.testing.FakeTokenSource
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PinningIosTest {
    private val site = "https://pub.dev"
    private val notItsKey = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun engineConfig(pins: Set<String>) =
        SinewHttp.client(SinewConfig(site, { "en" }, certificatePins = pins), FakeTokenSource(null)).engine.config
            as DarwinClientEngineConfig

    /** What Sinew owns on iOS: the preset installs Ktor's pinner as the session's challenge handler. */
    @Test
    fun thePresetInstallsThePinnerOnDarwin() {
        assertNotNull(engineConfig(setOf(notItsKey)).challengeHandler)
        assertNull(engineConfig(emptySet()).challengeHandler)
    }

    @Test
    fun theHandBuiltHelperInstallsItToo() {
        val client = SinewHttp.client(SinewConfig(site, { "en" }), FakeTokenSource(null), Darwin) {
            engine { pinCertificates("pub.dev", setOf(notItsKey)) }
        }
        assertNotNull((client.engine.config as DarwinClientEngineConfig).challengeHandler)
    }

    /**
     * Against a real public site, where the simulator can verify public certificates: reachable unpinned, refused once
     * pinned to a key it doesn't present. Gradle's headless simulator test runner can't verify them (every TLS request
     * fails with -1200), so there it reports the skip and checks nothing.
     */
    @Test
    fun aPinnedClientRefusesAChainWithoutThePinnedKey() = runBlocking {
        val probe = runCatching { SinewHttp.client(SinewConfig(site, { "en" }), FakeTokenSource(null)).get("robots.txt") }
        if (probe.isFailure) {
            println("PinningIosTest: skipped, $site isn't reachable with verified TLS here: ${probe.exceptionOrNull()?.message?.take(80)}")
            return@runBlocking
        }
        val pinned = SinewHttp.client(SinewConfig(site, { "en" }, certificatePins = setOf(notItsKey)), FakeTokenSource(null))
        val result = processCall("ORD", "GET", ExceptionLayer.Repository, apiHandlers("ORD", "GET")) {
            pinned.get("robots.txt").status.value // reached the server: a Success, and the test fails
        }
        assertIs<UnexpectedException>((result as Result.Failure).error)
    }
}
