package com.srctool.sinew.network

import com.srctool.sinew.exception.ApiErrorException
import com.srctool.sinew.exception.ConflictException
import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.ForbiddenException
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.NotFoundException
import com.srctool.sinew.exception.ParseException
import com.srctool.sinew.exception.RateLimitedException
import com.srctool.sinew.exception.RequestTimeOutException
import com.srctool.sinew.exception.ServerException
import com.srctool.sinew.exception.ServiceUnavailableException
import com.srctool.sinew.exception.UnauthorizedException
import com.srctool.sinew.exception.UndefinedErrorResponseException
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.exception.envelopeDataMissing
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.Envelope
import com.srctool.sinew.models.EnvelopeFailure
import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpHeaders
import io.ktor.serialization.ContentConvertException
import kotlinx.serialization.SerializationException

/**
 * The source guard for an HTTP call: [call] returns the response's envelope, and the result is a [Result] with a
 * classified [SinewException] on failure. Never throws, except to propagate the caller's own cancellation.
 *
 * - A failed status, a timeout or no connection goes through [apiHandlers], first match wins.
 * - An envelope whose `failure()` is set fails as a `ValidationException` (with field errors) or an
 *   `ApiErrorException`, before `toDomain()`.
 * - `toDomain()` runs inside the guard, so a mapping bug becomes a reported `ParseException` or
 *   `UnexpectedException`.
 * - The envelope's `message()` becomes `Result.Success.message`.
 *
 * The client needs `HttpResponseValidator { sinewErrorBody() }` to give a failed response its readable message.
 * Without it, Ktor's own `expectSuccess` errors are classified by status alone.
 *
 * ```kotlin
 * suspend fun cancel(id: String): Result<Order> = processApiCall("ORD", "CANCEL") { api.cancel(id) }
 * ```
 */
public suspend fun <R> processApiCall(module: String, function: String, call: suspend () -> Envelope<R>): Result<R> =
    guardApiCall(module, function, notFoundIsNull = false, call).castSuccess()

/** [processApiCall] for a lookup that may not exist: a 404 is `Success(null)`, every other failure is unchanged. */
public suspend fun <R> processOptionalApiCall(module: String, function: String, call: suspend () -> Envelope<R>): Result<R?> =
    guardApiCall(module, function, notFoundIsNull = true, call)

private suspend fun <R> guardApiCall(
    module: String,
    function: String,
    notFoundIsNull: Boolean,
    call: suspend () -> Envelope<R>,
): Result<R?> {
    var message = ""
    val result = processCall(module, function, ExceptionLayer.Repository, apiHandlers(module, function)) {
        val envelope = try {
            call()
        } catch (e: Throwable) {
            if (notFoundIsNull && e.statusError()?.status == 404) return@processCall null
            throw e
        }
        envelope.failure()?.let { throw it.toSinewException(module, function) }
        message = envelope.message().orEmpty()
        envelope.toDomain()
    }
    return if (result is Result.Success) result.copy(message = message) else result
}

@Suppress("UNCHECKED_CAST")
private fun <R> Result<R?>.castSuccess(): Result<R> = this as Result<R>

private fun EnvelopeFailure.toSinewException(module: String, function: String): SinewException {
    val fields = fieldErrors?.takeIf { it.isNotEmpty() }
    return if (fields != null) {
        ValidationException(module, ExceptionLayer.Repository, function, fields, serverOr(message, VALIDATION))
    } else {
        ApiErrorException(module, ExceptionLayer.Repository, function, serverOr(message, GENERIC))
    }
}

private val VALIDATION = ErrorMessage.Local("sinew.error.validation", fallback = "Please check the highlighted fields.")
private val GENERIC = ErrorMessage.Local("sinew.error.generic", fallback = "Something went wrong.")

/** The backend's text when it's readable, otherwise [local]. */
private fun serverOr(text: String?, local: ErrorMessage.Local): ErrorMessage =
    text?.takeIf { it.isNotBlank() }?.let { ErrorMessage.Server(it) } ?: local

/**
 * The HTTP handler table, first match wins: timeout, no connection, a classified status, unreadable JSON, a missing
 * envelope payload. Public, so an app can guard a call Sinew doesn't wrap with `processCall` and these handlers.
 *
 * | Matches | Becomes |
 * |---|---|
 * | a request, connect or socket timeout, also when an engine wraps it in a cancellation | `RequestTimeOutException` |
 * | no connection, DNS failure, connection refused or reset | `NoInternetConnectionException` |
 * | 400 or 422 with field errors | `ValidationException` |
 * | 401, 403, 404, 409 | `UnauthorizedException`, `ForbiddenException`, `NotFoundException`, `ConflictException` |
 * | 429 | `RateLimitedException` with `Retry-After` |
 * | 503, 504 / any other 5xx | `ServiceUnavailableException` / `ServerException` |
 * | any other status with a readable message / without | `ApiErrorException` / `UndefinedErrorResponseException` |
 * | JSON that doesn't fit the DTO | `ParseException`, reported |
 * | an envelope with no payload | `ParseException`, reported |
 *
 * A readable error-body message is the exception's server text; otherwise its type's local message is used. A TLS
 * or certificate failure matches nothing and becomes a reported `UnexpectedException`: a security event, never
 * "offline". So does a 408 status, which the server sent and so isn't a client timeout.
 */
public fun apiHandlers(module: String, function: String): List<ExceptionHandler> {
    val layer = ExceptionLayer.Repository
    return listOf(
        ExceptionHandler(::isTimeoutFailure) { RequestTimeOutException(module, layer, function, cause = it) },
        ExceptionHandler(::isNoConnectionFailure) { NoInternetConnectionException(module, layer, function, cause = it) },
        ExceptionHandler({ it.statusError() != null }) { statusException(module, function, it.statusError()!!, it) },
        ExceptionHandler(::isUnreadableJson) { ParseException(module, layer, function, cause = it) },
        envelopeDataMissing(module, function, layer),
    )
}

private fun statusException(module: String, function: String, error: HttpStatusError, cause: Throwable): SinewException {
    val layer = ExceptionLayer.Repository
    val server = error.errorBody?.message?.takeIf { it.isNotBlank() }?.let { ErrorMessage.Server(it) }
    val fields = error.errorBody?.fieldErrors?.takeIf { it.isNotEmpty() }
    val status = error.status
    if ((status == 400 || status == 422) && fields != null) {
        return ValidationException(module, layer, function, fields, server ?: VALIDATION, cause)
    }
    return when (status) {
        401 -> if (server != null) UnauthorizedException(module, layer, function, server, cause) else UnauthorizedException(module, layer, function, cause = cause)
        403 -> if (server != null) ForbiddenException(module, layer, function, server, cause) else ForbiddenException(module, layer, function, cause = cause)
        404 -> if (server != null) NotFoundException(module, layer, function, server, cause) else NotFoundException(module, layer, function, cause = cause)
        409 -> if (server != null) ConflictException(module, layer, function, server, cause) else ConflictException(module, layer, function, cause = cause)
        429 -> if (server != null) RateLimitedException(module, layer, function, error.retryAfter, server, cause) else RateLimitedException(module, layer, function, error.retryAfter, cause = cause)
        503, 504 -> if (server != null) ServiceUnavailableException(module, layer, function, status, server, cause) else ServiceUnavailableException(module, layer, function, status, cause = cause)
        in 500..599 -> if (server != null) ServerException(module, layer, function, status, server, cause) else ServerException(module, layer, function, status, cause = cause)
        else -> if (server != null) ApiErrorException(module, layer, function, server, cause) else UndefinedErrorResponseException(module, layer, function, status, cause)
    }
}

/**
 * The failed response in [this], from `sinewErrorBody` (with its body read) or from Ktor's own `expectSuccess`
 * errors (by status only: Ktor's handlers can't read the body here).
 */
internal fun Throwable.statusError(): HttpStatusError? = when (this) {
    is HttpStatusError -> this
    is ResponseException -> HttpStatusError(response.status.value, null, parseRetryAfter(response.headers[HttpHeaders.RetryAfter]))
    else -> null
}

private fun isUnreadableJson(e: Throwable): Boolean =
    e is SerializationException || e is ContentConvertException || e is NoTransformationFoundException

/** [this] and its causes, at most a few deep: engines wrap their failures. */
internal fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(8)

internal fun isTimeoutFailure(e: Throwable): Boolean = e.causeChain().any(::isTimeout)

/**
 * No connection anywhere in the chain, and no TLS or certificate failure anywhere in it or among its suppressed
 * exceptions: that's never "offline". OkHttp throws the first route's failure and attaches the others as suppressed,
 * so a pinning failure on one address can sit behind a refused connection to another.
 */
internal fun isNoConnectionFailure(e: Throwable): Boolean =
    e.statusError() == null && e.withSuppressed().none(::isSecurityFailure) && e.causeChain().any(::isNoConnection)

/** [causeChain], with each exception's suppressed exceptions beside it. */
private fun Throwable.withSuppressed(): Sequence<Throwable> =
    causeChain().flatMap { sequenceOf(it) + it.suppressedExceptions.asSequence().flatMap { s -> s.causeChain() } }
