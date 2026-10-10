package com.srctool.sinew.models

/** The layer a failure was translated in. Its [code] is part of every [SinewException.code]. */
public enum class ExceptionLayer(public val code: String) {
    ViewModel("VM"),
    UseCase("UC"),
    Repository("R"),
    Utility("UT"),
}

/** Who provides a failure's user-facing text. */
public sealed interface ErrorMessage {
    /** The backend's text, already localized from the request's `Accept-Language`. Shown as is. */
    public data class Server(val text: String) : ErrorMessage

    /** A message key with named arguments, resolved by the app's localizer at render time. */
    public data class Local(val key: String, val args: Map<String, Any> = emptyMap(), val fallback: String) : ErrorMessage
}

/**
 * A failure translated once, where it happened. [code] reads `module-layer-function-type`, so a support report
 * points at the exact call. The user-facing text is [errorMessage], rendered at display time; it's not named
 * `message` because `Throwable.message` is already a `String?`.
 */
public abstract class SinewException(
    public val module: String,
    public val layer: ExceptionLayer,
    public val function: String,
    cause: Throwable? = null,
) : Exception(cause) {
    public abstract val typeCode: String
    public abstract val errorMessage: ErrorMessage
    public open val retryable: Boolean get() = false

    public val code: String get() = "$module-${layer.code}-$function-$typeCode"

    /** The server text, or the English fallback with its arguments filled in, so logs read naturally. */
    override val message: String
        get() = when (val m = errorMessage) {
            is ErrorMessage.Server -> m.text
            is ErrorMessage.Local -> m.args.entries.fold(m.fallback) { text, (name, value) -> text.replace("{$name}", value.toString()) }
        }
}

/**
 * Thrown by an object envelope whose payload is absent. A plain error, not an [SinewException]: the envelope doesn't
 * know the guard's module or function, so the guard's handler turns it into a `ParseException`.
 */
public class EnvelopeDataMissing : IllegalStateException("Envelope has no data")
