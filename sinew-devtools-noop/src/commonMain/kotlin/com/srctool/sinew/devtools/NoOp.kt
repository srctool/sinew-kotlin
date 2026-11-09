package com.srctool.sinew.devtools

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.models.SinewException
import com.srctool.sinew.network.SinewHttpHooks
import com.srctool.sinew.storage.KeyValueStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The no-op stand-in for `sinew-devtools`: the same public API, checked against the real one's ABI dump in CI, with
// nothing behind it. Nothing is recorded, no rule applies, no host is overridden, and the hooks are
// `SinewHttpHooks.None`. Where a value must come back, it's the safe one: everything counts as sensitive, and no body
// is kept.

public class Redaction private constructor(
    public val headers: Set<String>,
    public val fieldParts: Set<String>,
    public val exactFields: Set<String>,
    public val mask: String,
    public val maxBodyBytes: Int,
) {
    public class Extension internal constructor(
        public val headers: MutableSet<String> = mutableSetOf(),
        public val fieldParts: MutableSet<String> = mutableSetOf(),
        public val exactFields: MutableSet<String> = mutableSetOf(),
        public var maxBodyBytes: Int? = null,
    )

    public operator fun plus(extend: Extension.() -> Unit): Redaction = this

    public fun withHeaders(names: Collection<String>): Redaction = this

    public fun isSensitive(name: String): Boolean = true

    public fun isSensitiveHeader(name: String): Boolean = true

    public fun redactHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.mapValues { (_, values) -> values.map { mask } }

    public fun redactText(text: String): String = mask

    public fun redactUrl(url: String): String = url.substringBefore('?').substringBefore('#')

    public fun redactBody(bytes: ByteArray?, contentType: String?): RecordedBody = RecordedBody.None

    public companion object {
        public const val DEFAULT_MAX_BODY_BYTES: Int = 64 * 1024

        public val Default: Redaction = Redaction(emptySet(), emptySet(), emptySet(), "•••", DEFAULT_MAX_BODY_BYTES)

        public fun maskSecret(value: String): String = "•••• (${value.length} chars)"

        public const val SHOW_TAIL_FROM: Int = 16
    }
}

public sealed interface RecordedBody {
    public data object None : RecordedBody

    public data class Text(val text: String, val size: Long, val truncated: Boolean, val masked: Boolean = false) : RecordedBody

    public data class Binary(val contentType: String?, val size: Long) : RecordedBody
}

public class NetworkExchange(
    public val method: String,
    public val url: String,
    public val requestHeaders: Map<String, List<String>> = emptyMap(),
    public val requestBody: ByteArray? = null,
    public val requestBodyStreamed: Boolean = false,
    public val status: Int? = null,
    public val responseHeaders: Map<String, List<String>> = emptyMap(),
    public val responseBody: ByteArray? = null,
    public val startedAt: Instant,
    public val duration: Duration? = null,
    public val error: Throwable? = null,
    public val clientHeaders: Set<String> = emptySet(),
    public val isReplay: Boolean = false,
)

public data class NetworkRecord(
    val id: Long,
    val startedAt: Instant,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: RecordedBody,
    val status: Int?,
    val responseHeaders: Map<String, List<String>>,
    val responseBody: RecordedBody,
    val duration: Duration?,
    val errorType: String?,
    val errorCode: String?,
    val cacheSource: String?,
    val isReplay: Boolean,
    val replayable: Boolean,
) {
    val fromCache: Boolean get() = cacheSource != null

    public fun asText(): String = ""

    public fun asCurl(): String = ""
}

public class NetworkRecorder(
    public val capacity: Int = DEFAULT_CAPACITY,
    redaction: Redaction = Redaction.Default,
) {
    public var redaction: Redaction = redaction

    public val records: StateFlow<List<NetworkRecord>> = MutableStateFlow<List<NetworkRecord>>(emptyList()).asStateFlow()

    /** Stores nothing: the record only goes back to the caller. */
    public fun record(exchange: NetworkExchange): NetworkRecord = NetworkRecord(
        0, exchange.startedAt, exchange.method, "", emptyMap(), RecordedBody.None, exchange.status, emptyMap(),
        RecordedBody.None, exchange.duration, null, null, null, exchange.isReplay, replayable = false,
    )

    public fun recordCacheServe(
        key: String,
        source: String,
        body: ByteArray? = null,
        contentType: String? = null,
        at: Instant = Clock.System.now(),
    ): NetworkRecord = NetworkRecord(
        0, at, "CACHE", "", emptyMap(), RecordedBody.None, null, emptyMap(), RecordedBody.None, null, null, null, source,
        isReplay = false, replayable = false,
    )

    public fun clear() {}

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 200
    }
}

public enum class ExceptionSource { Reported, Uncaught }

public data class ExceptionRecord(
    val id: Long,
    val firstAt: Instant,
    val lastAt: Instant,
    val type: String,
    val code: String?,
    val message: String,
    val stackTrace: String,
    val count: Int,
    val source: ExceptionSource,
) {
    public fun asText(): String = ""
}

public class ExceptionRecorder(
    public val capacity: Int = DEFAULT_CAPACITY,
    public val dedupeWindow: Duration = DEFAULT_DEDUPE_WINDOW,
    redaction: Redaction = Redaction.Default,
    @Suppress("unused") private val clock: Clock = Clock.System,
) {
    public var redaction: Redaction = redaction

    public val records: StateFlow<List<ExceptionRecord>> = MutableStateFlow<List<ExceptionRecord>>(emptyList()).asStateFlow()

    public fun record(error: Throwable, source: ExceptionSource = ExceptionSource.Reported) {}

    public fun clear() {}

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 50

        public val DEFAULT_DEDUPE_WINDOW: Duration = 2.seconds
    }
}

/** Passes every report straight to [wrapping]. */
public class DevToolsCrashReporter(
    private val wrapping: CrashReporter,
    @Suppress("unused") private val recorder: ExceptionRecorder = DevTools.exceptions,
) : CrashReporter {
    override fun recordNonFatal(error: SinewException) {
        wrapping.recordNonFatal(error)
    }
}

public data class FaultRule(
    val method: String? = null,
    val pathPattern: String,
    val status: Int? = null,
    val delay: Duration? = null,
    val drop: Boolean = false,
    val body: String? = null,
) {
    public fun matches(method: String, path: String): Boolean = false
}

public class FaultRules {
    public val active: StateFlow<List<FaultRule>> = MutableStateFlow<List<FaultRule>>(emptyList()).asStateFlow()

    public fun add(rule: FaultRule) {}

    public fun remove(rule: FaultRule) {}

    public fun clear() {}

    public fun match(method: String, path: String): FaultRule? = null
}

public class HostOverride {
    public val hosts: StateFlow<List<String>> = MutableStateFlow<List<String>>(emptyList()).asStateFlow()

    public val current: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    public suspend fun configure(hosts: List<String>, store: KeyValueStore? = null) {}

    public suspend fun select(host: String?) {}
}

public object DevTools {
    public val network: NetworkRecorder = NetworkRecorder()

    public val exceptions: ExceptionRecorder = ExceptionRecorder()

    public val faults: FaultRules = FaultRules()

    public val hosts: HostOverride = HostOverride()

    /** [SinewHttpHooks.None]: every request is left alone. */
    public val httpHooks: SinewHttpHooks get() = SinewHttpHooks.None

    public fun canReplay(record: NetworkRecord): Boolean = false

    public suspend fun replay(record: NetworkRecord) {}

    public fun expireAccessToken() {}

    public val coroutineHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> }

    public val isOpen: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    public fun open() {}

    public fun close() {}

    public fun captureUncaughtErrors() {}

    public fun clearUserData() {}
}
