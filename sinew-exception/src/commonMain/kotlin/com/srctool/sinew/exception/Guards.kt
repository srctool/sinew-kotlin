package com.srctool.sinew.exception

import com.srctool.sinew.models.EnvelopeDataMissing
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/** One rule in a guard's handler list: "is this a timeout?" and "make a RequestTimeOutException for this call". */
public class ExceptionHandler(
    public val matches: (Throwable) -> Boolean,
    public val transform: (Throwable) -> SinewException,
)

/** The handler every list ends with: an envelope with no payload becomes a [ParseException] for this call. */
public fun envelopeDataMissing(module: String, function: String, layer: ExceptionLayer): ExceptionHandler =
    ExceptionHandler({ it is EnvelopeDataMissing }) { ParseException(module, layer, function, cause = it) }

/**
 * The guard core every guard is built on, public so an app can guard calls Sinew doesn't know about.
 *
 * Cancellation comes first: when the caller itself was cancelled, the `CancellationException` propagates and is
 * never a failure. A [SinewException] from a lower layer passes through unchanged. Anything else goes to the first
 * matching handler, or becomes an [UnexpectedException]; unexpected and parse failures are reported.
 */
public suspend fun <T> processCall(
    module: String,
    function: String,
    layer: ExceptionLayer,
    handlers: List<ExceptionHandler>,
    block: suspend () -> T,
): Result<T> = try {
    Result.Success(block())
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()                   // the caller was cancelled: this throws, and cancellation propagates
    classify(module, function, layer, handlers, e)             // the caller is still active: an engine-wrapped timeout, classified
} catch (e: SinewException) {
    Result.Failure(e)
} catch (e: Throwable) {
    classify(module, function, layer, handlers, e)
}

/** The use-case guard: no handlers, so repository failures and domain exceptions pass through, and a bug becomes `…-UC-…-GEN`. */
public suspend fun <T> processResult(
    module: String,
    function: String,
    layer: ExceptionLayer = ExceptionLayer.UseCase,
    block: suspend () -> T,
): Result<T> = processCall(module, function, layer, emptyList(), block)

private fun classify(module: String, function: String, layer: ExceptionLayer, handlers: List<ExceptionHandler>, e: Throwable): Result.Failure {
    val mapped = runHandlers(handlers, e) ?: UnexpectedException(module, layer, function, cause = e)
    if (mapped is UnexpectedException || mapped is ParseException) reportSafely(mapped)
    return Result.Failure(mapped)
}

/** First match wins. A handler that throws counts as unmatched, so a broken handler never crashes the app. */
private fun runHandlers(handlers: List<ExceptionHandler>, e: Throwable): SinewException? {
    for (handler in handlers) {
        val mapped = try {
            if (handler.matches(e)) handler.transform(e) else null
        } catch (_: Throwable) {
            null
        }
        if (mapped != null) return mapped
    }
    return null
}

/** Reporting never turns a handled failure into a crash. */
private fun reportSafely(error: SinewException) {
    try {
        CrashReporter.current.recordNonFatal(error)
    } catch (_: Throwable) {
    }
}
