package com.srctool.sinew.devtools

import com.srctool.sinew.testing.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FaultRulesAndHostsTest {
    @Test
    fun aPatternMatchesTheEndOfThePathAtASlash() {
        val rule = FaultRule(pathPattern = "/orders", status = 500)
        assertTrue(rule.matches("GET", "/v1/orders"))
        assertTrue(rule.matches("GET", "/orders"))
        assertFalse(rule.matches("GET", "/v1/myorders"))
        assertFalse(rule.matches("GET", "/v1/orders/7"))
        assertTrue(FaultRule(pathPattern = "orders", status = 500).matches("GET", "/v1/orders"))
    }

    @Test
    fun aStarMatchesAnyRun() {
        val rule = FaultRule(pathPattern = "/orders/*", status = 500)
        assertTrue(rule.matches("GET", "/v1/orders/7"))
        assertTrue(rule.matches("GET", "/v1/orders/7/items"))
        assertFalse(rule.matches("GET", "/v1/orders"))
        assertTrue(FaultRule(pathPattern = "*", status = 500).matches("GET", "/anything/at/all"))
        assertTrue(FaultRule(pathPattern = "/a.b", status = 500).matches("GET", "/a.b"))
        assertFalse(FaultRule(pathPattern = "/a.b", status = 500).matches("GET", "/axb"))     // a dot is a dot
    }

    @Test
    fun aMethodNarrowsTheRule() {
        val rule = FaultRule(method = "post", pathPattern = "/orders", status = 500)
        assertTrue(rule.matches("POST", "/v1/orders"))
        assertFalse(rule.matches("GET", "/v1/orders"))
    }

    @Test
    fun theNewestMatchingRuleWins() {
        val rules = FaultRules()
        val old = FaultRule(pathPattern = "*", status = 500)
        val new = FaultRule(pathPattern = "/orders", status = 503)
        rules.add(old)
        rules.add(new)
        assertEquals(new, rules.match("GET", "/v1/orders"))
        assertEquals(old, rules.match("GET", "/v1/me"))
        rules.remove(new)
        assertEquals(old, rules.match("GET", "/v1/orders"))
        rules.clear()
        assertNull(rules.match("GET", "/v1/orders"))
    }

    @Test
    fun aRuleIsValidated() {
        assertFailsWith<IllegalArgumentException> { FaultRule(pathPattern = "/a") }
        assertFailsWith<IllegalArgumentException> { FaultRule(pathPattern = " ", status = 500) }
        assertFailsWith<IllegalArgumentException> { FaultRule(pathPattern = "/a", status = 500, drop = true) }
        assertFailsWith<IllegalArgumentException> { FaultRule(pathPattern = "/a", status = 600) }
        assertFailsWith<IllegalArgumentException> { FaultRule(pathPattern = "/a", delay = Duration.ZERO) }
        FaultRule(pathPattern = "/a", delay = 1.seconds)
        FaultRule(pathPattern = "/a", drop = true)
    }

    @Test
    fun aRuleBecomesTheNetworksDecision() {
        val decision = FaultRule(pathPattern = "/a", status = 503, delay = 2.seconds, body = "{}").decision()
        assertEquals(503, decision.status)
        assertEquals(2.seconds, decision.delay)
        assertEquals("{}", decision.body)
        assertFalse(decision.drop)
        assertTrue(FaultRule(pathPattern = "/a", drop = true).decision().drop)
    }

    // Host override

    private val staging = "https://staging.local/v2"
    private val local = "http://10.0.2.2:8080/v1"

    @Test
    fun theChosenHostIsRememberedAcrossRestarts() = runTest {
        val store = FakeKeyValueStore()
        HostOverride().apply {
            configure(listOf(staging, local), store)
            assertNull(current.value)
            select(local)
            assertEquals(local, current.value)
        }
        assertEquals(local, store.getString(HostOverride.KEY))
        val restarted = HostOverride().apply { configure(listOf(staging, local), store) }
        assertEquals(local, restarted.current.value)
        restarted.select(null)                                              // the reset button
        assertNull(restarted.current.value)
        assertNull(store.getString(HostOverride.KEY))
    }

    @Test
    fun aHostTheAppNoLongerListsIsForgotten() = runTest {
        val store = FakeKeyValueStore()
        store.setString(HostOverride.KEY, "https://old.local/v1")
        val hosts = HostOverride().apply { configure(listOf(staging), store) }
        assertNull(hosts.current.value)
        assertNull(store.getString(HostOverride.KEY))
    }

    @Test
    fun onlyAListedHostCanBeChosen() = runTest {
        val hosts = HostOverride().apply { configure(listOf(staging)) }
        assertFailsWith<IllegalArgumentException> { hosts.select("https://evil.local") }
        hosts.select(staging)                                               // without a store, it's only kept in memory
        assertEquals(staging, hosts.current.value)
        assertEquals(listOf(staging), hosts.hosts.value)
    }

    @Test
    fun aHostMustBeAnHttpBaseUrl() = runTest {
        assertFailsWith<IllegalArgumentException> { HostOverride().configure(listOf("ftp://x.local")) }
        assertFailsWith<IllegalArgumentException> { HostOverride().configure(listOf("not a url")) }
    }
}
