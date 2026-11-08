package com.srctool.sinew.devtools

import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One request and its response as the network layer saw them, **before** redaction. It's handed to
 * [NetworkRecorder.record], which redacts it and keeps only the result. Content types are read from the headers.
 *
 * [clientHeaders] are the names the client adds by itself (its `defaultHeaders`): they're redacted, and a replay
 * gets them again from the client, so masking them doesn't stop a replay.
 */
public class NetworkExchange(
    public val method: String,
    public val url: String,
    public val requestHeaders: Map<String, List<String>> = emptyMap(),
    public val requestBody: ByteArray? = null,
    /** Whether the request body was streamed: it's recorded as binary of unknown size, and can't be replayed. */
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

/**
 * A redacted record in the Network Monitor. Nothing in it is a secret: [url], the headers and the bodies went through
 * [Redaction] before the record existed.
 */
public data class NetworkRecord(
    val id: Long,
    val startedAt: Instant,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: RecordedBody,
    /** The response status, or `null` when the request failed before a response. */
    val status: Int?,
    val responseHeaders: Map<String, List<String>>,
    val responseBody: RecordedBody,
    val duration: Duration?,
    /** The failure's type, such as `NoInternetConnectionException`. */
    val errorType: String?,
    /** The failure's Sinew code, when it's a `SinewException`. */
    val errorCode: String?,
    /** Where a cache serve came from (`memory`, `disk`), or `null` for a request that went out. */
    val cacheSource: String?,
    val isReplay: Boolean,
    /**
     * Whether the request kept everything it needs to be sent again: nothing in its URL, its own headers or its body
     * was masked, and the body wasn't cut or binary. `Authorization` and the client's own headers don't count, since
     * the client adds them again.
     */
    val replayable: Boolean,
) {
    /** Whether this record is a cache serve, not a request that went out. */
    val fromCache: Boolean get() = cacheSource != null

    /** The record as text, for a bug report. Every secret is still masked. */
    public fun asText(): String = buildString {
        appendLine("$method $url")
        val summary = buildList {
            add(status?.toString() ?: "no response")
            duration?.let { add("${it.inWholeMilliseconds} ms") }
            if (isReplay) add("replay")
            cacheSource?.let { add("from cache ($it)") }
        }
        appendLine("→ " + summary.joinToString(" · "))
        if (errorType != null) appendLine("Error: $errorType" + (errorCode?.let { " $it" } ?: ""))
        section("Request headers", requestHeaders)
        section("Request body", requestBody)
        section("Response headers", responseHeaders)
        section("Response body", responseBody)
    }.trimEnd()

    /**
     * A `curl` command for the request. Every secret is still masked, so a request that needs a token won't run as
     * copied: it's for a bug report.
     */
    public fun asCurl(): String {
        val notes = mutableListOf<String>()                 // after the command: a comment inside a continued line ends it
        val parts = buildList {
            add(if (method == "GET") "curl ${quote(url)}" else "curl -X $method ${quote(url)}")
            requestHeaders.forEach { (name, values) -> values.forEach { add("-H ${quote("$name: $it")}") } }
            when (val body = requestBody) {
                RecordedBody.None -> Unit
                is RecordedBody.Text -> {
                    add("--data-raw ${quote(body.text)}")
                    if (body.truncated) notes += "body cut at ${formatSize(body.text.encodeToByteArray().size.toLong())} of ${formatSize(body.size)}"
                }
                is RecordedBody.Binary -> notes += "body not included: ${body.contentType ?: "binary"}, ${formatSize(body.size)}"
            }
        }
        return parts.joinToString(" \\\n  ") + notes.joinToString("") { "\n# $it" }
    }

    private fun StringBuilder.section(title: String, headers: Map<String, List<String>>) {
        if (headers.isEmpty()) return
        appendLine().appendLine(title)
        headers.forEach { (name, values) -> values.forEach { appendLine("$name: $it") } }
    }

    private fun StringBuilder.section(title: String, body: RecordedBody) {
        when (body) {
            RecordedBody.None -> Unit
            is RecordedBody.Text -> {
                appendLine().appendLine(title).appendLine(body.text)
                if (body.truncated) appendLine("(cut at ${formatSize(body.text.encodeToByteArray().size.toLong())} of ${formatSize(body.size)})")
            }
            is RecordedBody.Binary -> appendLine().appendLine(title).appendLine("${body.contentType ?: "binary"}, ${formatSize(body.size)} (not recorded)")
        }
    }

    private companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/**
 * The Network Monitor's records, in memory only, newest first. Each exchange is redacted before it's stored, so no
 * record holds a secret. Holds at most [capacity] records and drops the oldest.
 */
@OptIn(ExperimentalAtomicApi::class)
public class NetworkRecorder(
    public val capacity: Int = DEFAULT_CAPACITY,
    redaction: Redaction = Redaction.Default,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /** What's hidden before a record is stored. `SinewDevTools(redaction = …)` sets the app's rules. */
    @Volatile
    public var redaction: Redaction = redaction

    private val ids = AtomicLong(0)
    private val _records = MutableStateFlow<List<NetworkRecord>>(emptyList())

    /** The records, newest first. */
    public val records: StateFlow<List<NetworkRecord>> = _records.asStateFlow()

    /** Redacts [exchange] and stores the result. */
    public fun record(exchange: NetworkExchange): NetworkRecord {
        val rules = redaction.withHeaders(exchange.clientHeaders)
        val requestBody = if (exchange.requestBodyStreamed) {
            RecordedBody.Binary(exchange.requestHeaders.contentType()?.substringBefore(';')?.trim(), UNKNOWN_SIZE)
        } else {
            rules.redactBody(exchange.requestBody, exchange.requestHeaders.contentType())
        }
        val url = rules.redactUrl(exchange.url)
        val readded = exchange.clientHeaders.mapTo(mutableSetOf()) { it.lowercase() } + "authorization"
        val headersKept = exchange.requestHeaders.keys.none { it.lowercase() !in readded && rules.isSensitiveHeader(it) }
        val bodyKept = when (requestBody) {
            RecordedBody.None -> true
            is RecordedBody.Text -> !requestBody.truncated && !requestBody.masked
            is RecordedBody.Binary -> false
        }
        val error = exchange.error
        return add(
            NetworkRecord(
                id = ids.incrementAndFetch(),
                startedAt = exchange.startedAt,
                method = exchange.method.uppercase(),
                url = url,
                requestHeaders = rules.redactHeaders(exchange.requestHeaders),
                requestBody = requestBody,
                status = exchange.status,
                responseHeaders = rules.redactHeaders(exchange.responseHeaders),
                responseBody = rules.redactBody(exchange.responseBody, exchange.responseHeaders.contentType()),
                duration = exchange.duration,
                errorType = error?.let { it::class.simpleName ?: "Throwable" },
                errorCode = (error as? SinewException)?.code,
                cacheSource = null,
                isReplay = exchange.isReplay,
                replayable = url == exchange.url && headersKept && bodyKept,
            ),
        )
    }

    /**
     * Records that the app served [key] from its cache ([source]: `memory`, `disk`) instead of the network. The body
     * goes through the same redaction: a cached response is still a response. A cache serve is never replayable.
     */
    public fun recordCacheServe(
        key: String,
        source: String,
        body: ByteArray? = null,
        contentType: String? = null,
        at: Instant = Clock.System.now(),
    ): NetworkRecord = add(
        NetworkRecord(
            id = ids.incrementAndFetch(),
            startedAt = at,
            method = "CACHE",
            url = redaction.redactUrl(key),
            requestHeaders = emptyMap(),
            requestBody = RecordedBody.None,
            status = null,
            responseHeaders = emptyMap(),
            responseBody = redaction.redactBody(body, contentType),
            duration = null,
            errorType = null,
            errorCode = null,
            cacheSource = source,
            isReplay = false,
            replayable = false,
        ),
    )

    /** Forgets every record: on "Clear", and on sign-out through `DevTools.clearUserData()`. */
    public fun clear() {
        _records.value = emptyList()
    }

    private fun add(record: NetworkRecord): NetworkRecord {
        _records.update { (listOf(record) + it).take(capacity) }
        return record
    }

    private fun Map<String, List<String>>.contentType(): String? =
        entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()

    public companion object {
        /** The default number of records kept. */
        public const val DEFAULT_CAPACITY: Int = 200
    }
}

/** The size of a streamed body, which isn't read. */
internal const val UNKNOWN_SIZE: Long = -1

/** A size as the pages show it: `512 B`, `12 KB`, `1.2 MB`, or `unknown size` for a streamed body. */
internal fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> {
        val tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024)
        if (tenths % 10 == 0L) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
    }
}
