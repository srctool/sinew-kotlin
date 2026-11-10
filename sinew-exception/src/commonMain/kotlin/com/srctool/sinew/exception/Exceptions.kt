package com.srctool.sinew.exception

import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.SinewException
import kotlin.time.Duration

// The built-in classification. Every type takes the module, layer and function of the guard that creates it.
// A type whose text is always local has a fixed message. A "server if readable" type takes an errorMessage that
// defaults to its local key, so a handler passes ErrorMessage.Server only when the body had readable text.
// The codes and the `sinew.` key prefix are reserved; an app's own exceptions use other codes and keys.
// There's no CancelException on Kotlin: cancellation propagates, it's never a failure.

private fun local(key: String, fallback: String, args: Map<String, Any> = emptyMap()) = ErrorMessage.Local("sinew.error.$key", args, fallback)

// Connectivity

/** There is no network connection. */
public class NoInternetConnectionException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `NIC`, the last part of [code]. */
    override val typeCode: String = "NIC"
    /** `true`: the same call may succeed when tried again. */
    override val retryable: Boolean = true
    /** The local message `sinew.error.noInternet`. */
    override val errorMessage: ErrorMessage = local("noInternet", "You're offline. Check your connection and try again.")
}

/** The request timed out. */
public class RequestTimeOutException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `RTO`, the last part of [code]. */
    override val typeCode: String = "RTO"
    /** `true`: the same call may succeed when tried again. */
    override val retryable: Boolean = true
    /** The local message `sinew.error.timeout`. */
    override val errorMessage: ErrorMessage = local("timeout", "This is taking too long. Please try again.")
}

// HTTP responses

/**
 * A 400 or 422 with field messages. The form message is the backend's when it sent one; field messages are shown without a code.
 *
 * @property fieldErrors The field messages, by field name.
 * @property errorMessage The form message: the backend's when it sent one, otherwise a local message.
 */
public class ValidationException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val fieldErrors: Map<String, List<String>> = emptyMap(),
    /** The local message `sinew.error.validation`. */
    override val errorMessage: ErrorMessage = local("validation", "Please check the highlighted fields."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `VAL`, the last part of [code]. */
    override val typeCode: String = "VAL"
}

/**
 * A 401. [errorMessage] is the backend's text when it sent a readable one, otherwise a local message.
 *
 * @property errorMessage The backend's text when it sent a readable one, otherwise the local message `sinew.error.unauthorized`.
 */
public class UnauthorizedException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    /** The local message `sinew.error.unauthorized`. */
    override val errorMessage: ErrorMessage = local("unauthorized", "Your session has ended. Please sign in again."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `UNA`, the last part of [code]. */
    override val typeCode: String = "UNA"
}

/**
 * A 403. [errorMessage] is the backend's text when it sent a readable one, otherwise a local message.
 *
 * @property errorMessage The backend's text when it sent a readable one, otherwise the local message `sinew.error.forbidden`.
 */
public class ForbiddenException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    /** The local message `sinew.error.forbidden`. */
    override val errorMessage: ErrorMessage = local("forbidden", "You don't have access to this."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `FOR`, the last part of [code]. */
    override val typeCode: String = "FOR"
}

/**
 * A 404. [errorMessage] is the backend's text when it sent a readable one, otherwise a local message.
 *
 * @property errorMessage The backend's text when it sent a readable one, otherwise the local message `sinew.error.notFound`.
 */
public class NotFoundException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    /** The local message `sinew.error.notFound`. */
    override val errorMessage: ErrorMessage = local("notFound", "This item no longer exists."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `NTF`, the last part of [code]. */
    override val typeCode: String = "NTF"
}

/**
 * A 409. [errorMessage] is the backend's text when it sent a readable one, otherwise a local message.
 *
 * @property errorMessage The backend's text when it sent a readable one, otherwise the local message `sinew.error.conflict`.
 */
public class ConflictException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    /** The local message `sinew.error.conflict`. */
    override val errorMessage: ErrorMessage = local("conflict", "This was changed by someone else. Reload and try again."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `CFL`, the last part of [code]. */
    override val typeCode: String = "CFL"
}

/**
 * A 429. Its local message names the wait when the backend sent `Retry-After`.
 *
 * @property retryAfter The wait the backend asked for with `Retry-After`, or `null`.
 * @property errorMessage A local message that names the wait when [retryAfter] is known.
 */
public class RateLimitedException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val retryAfter: Duration?,
    override val errorMessage: ErrorMessage = rateLimitedMessage(retryAfter),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `RTL`, the last part of [code]. */
    override val typeCode: String = "RTL"
    /** `true`: the same call may succeed when tried again. */
    override val retryable: Boolean = true
}

private fun rateLimitedMessage(retryAfter: Duration?): ErrorMessage =
    if (retryAfter == null) {
        local("rateLimitedNoTime", "Too many attempts. Try again in a moment.")
    } else {
        local("rateLimited", "Too many attempts. Try again in {seconds} seconds.", mapOf("seconds" to retryAfter.inWholeSeconds))
    }

/**
 * Any 5xx except 503 and 504 (500, 501, 502, 505…): the backend's side.
 *
 * @property status The HTTP status.
 * @property errorMessage The backend's text when it sent a readable one, otherwise the local message `sinew.error.server`.
 */
public class ServerException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val status: Int,
    /** The local message `sinew.error.server`. */
    override val errorMessage: ErrorMessage = local("server", "Something went wrong on our side."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `SRV`, the last part of [code]. */
    override val typeCode: String = "SRV"
}

/**
 * A 503 or 504: the backend is busy or a gateway timed out, so a retry may succeed.
 *
 * @property status The HTTP status: 503 or 504.
 * @property errorMessage The backend's text when it sent a readable one, otherwise the local message `sinew.error.unavailable`.
 */
public class ServiceUnavailableException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val status: Int,
    /** The local message `sinew.error.unavailable`. */
    override val errorMessage: ErrorMessage = local("unavailable", "The service is busy. Please try again."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `SUN`, the last part of [code]. */
    override val typeCode: String = "SUN"
    /** `true`: the same call may succeed when tried again. */
    override val retryable: Boolean = true
}

/**
 * The backend reported a failure with a readable message: an envelope `failure()` or an error body. Always server text.
 *
 * @property errorMessage The backend's text.
 */
public class ApiErrorException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    override val errorMessage: ErrorMessage,
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    /** The type code `AE`, the last part of [code]. */
    override val typeCode: String = "AE"
}

/**
 * Any other non-2xx status without a readable message.
 *
 * @property status The HTTP status.
 */
public class UndefinedErrorResponseException(module: String, layer: ExceptionLayer, function: String, public val status: Int, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `UER`, the last part of [code]. */
    override val typeCode: String = "UER"
    /** The local message `sinew.error.generic`. */
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

/** A body that couldn't be parsed or mapped. Reported: it's a bug in the app or the backend. */
public class ParseException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `DF`, the last part of [code]. */
    override val typeCode: String = "DF"
    /** The local message `sinew.error.generic`. */
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

// Storage

/** Stored data that can't be read: a corrupted file, or a value that fails to decrypt. */
public class LocalStorageCorruptionException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `LSC`, the last part of [code]. */
    override val typeCode: String = "LSC"
    /** The local message `sinew.error.generic`. */
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

/** The device is out of space. */
public class StorageFullException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `SF`, the last part of [code]. */
    override val typeCode: String = "SF"
    /** The local message `sinew.error.storageFull`. */
    override val errorMessage: ErrorMessage = local("storageFull", "Your device is out of space.")
}

/** Any other storage failure while reading. */
public class StorageReadException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `SR`, the last part of [code]. */
    override val typeCode: String = "SR"
    /** `true`: the same call may succeed when tried again. */
    override val retryable: Boolean = true
    /** The local message `sinew.error.generic`. */
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

/** Any other storage failure while writing. */
public class StorageWriteException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `SW`, the last part of [code]. */
    override val typeCode: String = "SW"
    /** `true`: the same call may succeed when tried again. */
    override val retryable: Boolean = true
    /** The local message `sinew.error.generic`. */
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

// Other

/** A polled call didn't finish in time. The last failure, if there was one, is its cause. */
public class PollingTimeOutException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `PTO`, the last part of [code]. */
    override val typeCode: String = "PTO"
    /** `true`: the same call may succeed when tried again. */
    override val retryable: Boolean = true
    /** The local message `sinew.error.stillProcessing`. */
    override val errorMessage: ErrorMessage = local("stillProcessing", "This is still processing. Check again later.")
}

/** Anything no handler recognised: a bug. Reported. */
public class UnexpectedException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    /** The type code `GEN`, the last part of [code]. */
    override val typeCode: String = "GEN"
    /** The local message `sinew.error.generic`. */
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}
