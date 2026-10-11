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

public class NoInternetConnectionException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "NIC"
    override val retryable: Boolean = true
    override val errorMessage: ErrorMessage = local("noInternet", "You're offline. Check your connection and try again.")
}

public class RequestTimeOutException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "RTO"
    override val retryable: Boolean = true
    override val errorMessage: ErrorMessage = local("timeout", "This is taking too long. Please try again.")
}

// HTTP responses

/** A 400 or 422 with field messages. The form message is the backend's when it sent one; field messages are shown without a code. */
public class ValidationException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val fieldErrors: Map<String, List<String>> = emptyMap(),
    override val errorMessage: ErrorMessage = local("validation", "Please check the highlighted fields."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "VAL"
}

public class UnauthorizedException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    override val errorMessage: ErrorMessage = local("unauthorized", "Your session has ended. Please sign in again."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "UNA"
}

public class ForbiddenException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    override val errorMessage: ErrorMessage = local("forbidden", "You don't have access to this."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "FOR"
}

public class NotFoundException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    override val errorMessage: ErrorMessage = local("notFound", "This item no longer exists."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "NTF"
}

public class ConflictException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    override val errorMessage: ErrorMessage = local("conflict", "This was changed by someone else. Reload and try again."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "CFL"
}

/** A 429. Its local message names the wait when the backend sent `Retry-After`. */
public class RateLimitedException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val retryAfter: Duration?,
    override val errorMessage: ErrorMessage = rateLimitedMessage(retryAfter),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "RTL"
    override val retryable: Boolean = true
}

private fun rateLimitedMessage(retryAfter: Duration?): ErrorMessage =
    if (retryAfter == null) {
        local("rateLimitedNoTime", "Too many attempts. Try again shortly.")
    } else {
        local("rateLimited", "Too many attempts. Try again in {seconds} seconds.", mapOf("seconds" to retryAfter.inWholeSeconds))
    }

/** Any 5xx except 503 and 504 (500, 501, 502, 505…): the backend's side. */
public class ServerException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val status: Int,
    override val errorMessage: ErrorMessage = local("server", "Something went wrong on our side."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "SRV"
}

/** A 503 or 504: the backend is busy or a gateway timed out, so a retry may succeed. */
public class ServiceUnavailableException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    public val status: Int,
    override val errorMessage: ErrorMessage = local("unavailable", "The service is busy. Please try again."),
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "SUN"
    override val retryable: Boolean = true
}

/** The backend reported a failure with a readable message: an envelope `failure()` or an error body. Always server text. */
public class ApiErrorException(
    module: String,
    layer: ExceptionLayer,
    function: String,
    override val errorMessage: ErrorMessage,
    cause: Throwable? = null,
) : SinewException(module, layer, function, cause) {
    override val typeCode: String = "AE"
}

/** Any other non-2xx status without a readable message. */
public class UndefinedErrorResponseException(module: String, layer: ExceptionLayer, function: String, public val status: Int, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "UER"
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

/** A body that couldn't be parsed or mapped. Reported: it's a bug in the app or the backend. */
public class ParseException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "DF"
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

// Storage

public class LocalStorageCorruptionException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "LSC"
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

public class StorageFullException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "SF"
    override val errorMessage: ErrorMessage = local("storageFull", "Your device is out of space.")
}

public class StorageReadException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "SR"
    override val retryable: Boolean = true
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

public class StorageWriteException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "SW"
    override val retryable: Boolean = true
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}

// Other

public class PollingTimeOutException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "PTO"
    override val retryable: Boolean = true
    override val errorMessage: ErrorMessage = local("stillProcessing", "This is still processing. Check again later.")
}

/** Anything no handler recognised: a bug. Reported. */
public class UnexpectedException(module: String, layer: ExceptionLayer, function: String, cause: Throwable? = null) :
    SinewException(module, layer, function, cause) {
    override val typeCode: String = "GEN"
    override val errorMessage: ErrorMessage = local("generic", "Something went wrong.")
}
