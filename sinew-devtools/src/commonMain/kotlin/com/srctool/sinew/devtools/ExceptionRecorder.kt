package com.srctool.sinew.devtools

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.models.ErrorMessage
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How an exception reached the Exception Log. */
public enum class ExceptionSource {
    /** Through the `CrashReporter`: handled, but pointing at a bug. */
    Reported,

    /** Through an uncaught-error hook or `DevTools.coroutineHandler`. */
    Uncaught,
}

/**
 * A row in the Exception Log. Repeats within the recorder's window are merged into one row: [count] says how many,
 * [firstAt] and [lastAt] when. The message and the stack trace went through [Redaction.redactText].
 */
public data class ExceptionRecord(
    /** The row's id. */
    val id: Long,
    /** When the exception was first seen in this row. */
    val firstAt: Instant,
    /** When the exception was last seen in this row. */
    val lastAt: Instant,
    /** The exception's type, such as `ParseException`. */
    val type: String,
    /** The Sinew code (`ORD-R-list-PRS`), when it's a `SinewException`. */
    val code: String?,
    /** The message key of a local message, the server's text, or a plain exception's message. */
    val message: String,
    /** The stack trace, redacted. */
    val stackTrace: String,
    /** How many times the exception was seen: repeats within the window are merged. */
    val count: Int,
    /** How the exception was reported. */
    val source: ExceptionSource,
) {
    /** The record as text, for a bug report: already redacted. */
    public fun asText(): String = buildString {
        append(type)
        code?.let { append(" $it") }
        if (count > 1) append(" ×$count")
        appendLine()
        if (message.isNotEmpty()) appendLine(message)
        appendLine("$source · first $firstAt · last $lastAt")
        appendLine()
        append(stackTrace)
    }.trimEnd()
}

/**
 * The Exception Log's records, in memory only, newest first. An exception that repeats within [dedupeWindow] of the
 * last time it was seen (same type, code, message and source) is merged into that row, which moves to the top with
 * its count raised. Holds at most [capacity] rows and drops the oldest.
 */
@OptIn(ExperimentalAtomicApi::class)
public class ExceptionRecorder(
    /** The most rows kept; the oldest is dropped. */
    public val capacity: Int = DEFAULT_CAPACITY,
    /** How long after an exception was last seen a repeat is merged into its row. */
    public val dedupeWindow: Duration = DEFAULT_DEDUPE_WINDOW,
    redaction: Redaction = Redaction.Default,
    private val clock: Clock = Clock.System,
) {
    /** What's hidden before a row is stored. `SinewDevTools(redaction = …)` sets the app's rules. */
    @Volatile
    public var redaction: Redaction = redaction

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val ids = AtomicLong(0)
    private val _records = MutableStateFlow<List<ExceptionRecord>>(emptyList())

    /** The rows, newest first. */
    public val records: StateFlow<List<ExceptionRecord>> = _records.asStateFlow()

    /** Records [error], merging it into a recent identical row. */
    public fun record(error: Throwable, source: ExceptionSource = ExceptionSource.Reported) {
        val now = clock.now()
        val type = error::class.simpleName ?: "Throwable"
        val code = (error as? SinewException)?.code
        val message = redaction.redactText(
            when (error) {
                is SinewException -> when (val m = error.errorMessage) {
                    is ErrorMessage.Local -> m.key
                    is ErrorMessage.Server -> m.text
                }
                else -> error.message.orEmpty()
            },
        )
        _records.update { rows ->
            val repeat = rows.indexOfFirst {
                it.type == type && it.code == code && it.message == message && it.source == source && now - it.lastAt <= dedupeWindow
            }
            if (repeat >= 0) {
                val merged = rows[repeat].copy(lastAt = now, count = rows[repeat].count + 1)
                listOf(merged) + (rows.subList(0, repeat) + rows.subList(repeat + 1, rows.size))
            } else {
                val row = ExceptionRecord(
                    id = ids.incrementAndFetch(),
                    firstAt = now,
                    lastAt = now,
                    type = type,
                    code = code,
                    message = message,
                    stackTrace = redaction.redactText(error.stackTraceToString()),
                    count = 1,
                    source = source,
                )
                (listOf(row) + rows).take(capacity)
            }
        }
    }

    /** Forgets every row: on "Clear". */
    public fun clear() {
        _records.value = emptyList()
    }

    /** The defaults. */
    public companion object {
        /** The default number of rows kept. */
        public const val DEFAULT_CAPACITY: Int = 50

        /** The default window within which a repeat is merged. */
        public val DEFAULT_DEDUPE_WINDOW: Duration = 2.seconds
    }
}

/**
 * Wraps the app's real [CrashReporter]: every report goes to the Exception Log, then on to [wrapping] unchanged.
 * Install it at the composition root: `CrashReporter.install(DevToolsCrashReporter(wrapping = realReporter))`.
 */
public class DevToolsCrashReporter(
    private val wrapping: CrashReporter,
    private val recorder: ExceptionRecorder = DevTools.exceptions,
) : CrashReporter {
    /** Records [error] in the Exception Log, then passes it to the wrapped reporter. */
    override fun recordNonFatal(error: SinewException) {
        try {
            recorder.record(error, ExceptionSource.Reported)
        } finally {
            wrapping.recordNonFatal(error)                  // the real reporter gets every report, whatever devtools does
        }
    }
}
