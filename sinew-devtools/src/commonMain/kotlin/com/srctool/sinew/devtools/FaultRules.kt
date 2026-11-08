package com.srctool.sinew.devtools

import com.srctool.sinew.network.FaultDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/**
 * A fault the network layer applies, inside the auth layer, to requests that match [method] (any, when `null`) and
 * [pathPattern]. It waits [delay], then drops the connection ([drop]) or answers [status] with [body] without
 * reaching the backend. With only a delay, the request goes out after it.
 *
 * [pathPattern] matches the end of the path, at a `/`: `/orders` matches `/v1/orders` but not `/v1/myorders`. A `*`
 * in it matches any run of characters, `/` included, so `/orders/` followed by a `*` matches every order.
 */
public data class FaultRule(
    val method: String? = null,
    val pathPattern: String,
    val status: Int? = null,
    val delay: Duration? = null,
    val drop: Boolean = false,
    val body: String? = null,
) {
    init {
        require(pathPattern.isNotBlank()) { "pathPattern is blank" }
        require(status != null || drop || delay != null) { "a fault needs a status, a drop or a delay" }
        require(!(drop && status != null)) { "a fault either drops the connection or answers a status, not both" }
        require(status == null || status in 100..599) { "status must be an HTTP status, was $status" }
        require(delay == null || delay.isPositive()) { "delay must be positive, was $delay" }
    }

    private val regex = Regex(
        "^(.*/)?" + pathPattern.trimStart('/').split('*').joinToString(".*") { Regex.escape(it) } + "$",
    )

    /** Whether this rule applies to a request with [method] and the encoded [path]. */
    public fun matches(method: String, path: String): Boolean =
        (this.method == null || this.method.equals(method, ignoreCase = true)) && regex.matches(path)

    internal fun decision(): FaultDecision = FaultDecision(delay = delay, status = status, body = body, drop = drop)
}

/**
 * The active fault rules, in memory only: they're gone after a restart, so a rule forgotten on a test device can't
 * break the next day's session. When several match a request, the most recently added wins.
 */
public class FaultRules {
    private val _active = MutableStateFlow<List<FaultRule>>(emptyList())

    /** The rules, oldest first. The ribbon says "faults on" while it isn't empty. */
    public val active: StateFlow<List<FaultRule>> = _active.asStateFlow()

    /** Adds [rule]. */
    public fun add(rule: FaultRule) {
        _active.update { it + rule }
    }

    /** Removes [rule]. */
    public fun remove(rule: FaultRule) {
        _active.update { it - rule }
    }

    /** Removes every rule. */
    public fun clear() {
        _active.value = emptyList()
    }

    /** The rule for a request, or `null`: the newest one that matches. */
    public fun match(method: String, path: String): FaultRule? = _active.value.lastOrNull { it.matches(method, path) }
}
