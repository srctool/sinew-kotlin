package com.srctool.sinew.models

/**
 * What every guard, repository and use case returns. Sinew's own type rather than `kotlin.Result`: the failure is
 * always a typed [SinewException], and a success carries the backend's message, if it sent one. The helpers are named
 * like `kotlin.Result`'s, so code reads the same.
 */
public sealed interface Result<out T> {
    /**
     * The call succeeded.
     *
     * @property data What the call returned.
     * @property message The backend's text, or `""` when it sent none.
     */
    public data class Success<out T>(val data: T, val message: String = "") : Result<T>

    /**
     * The call failed.
     *
     * @property error The typed failure.
     */
    public data class Failure(val error: SinewException) : Result<Nothing>
}

/** Whether this is a [Result.Success]. */
public val Result<*>.isSuccess: Boolean get() = this is Result.Success

/** The data of a success, or `null` for a failure. */
public val <T> Result<T>.dataOrNull: T? get() = (this as? Result.Success)?.data

/** The failure's [SinewException], or `null` for a success. */
public val Result<*>.errorOrNull: SinewException? get() = (this as? Result.Failure)?.error

/** Returns the result of [onSuccess] for a success, or of [onFailure] for a failure. */
public inline fun <T, R> Result<T>.fold(onSuccess: (T) -> R, onFailure: (SinewException) -> R): R = when (this) {
    is Result.Success -> onSuccess(data)
    is Result.Failure -> onFailure(error)
}

/** Runs [action] on the data of a success, and returns this result. */
public inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> = also { if (it is Result.Success) action(it.data) }

/** Runs [action] on the exception of a failure, and returns this result. */
public inline fun <T> Result<T>.onFailure(action: (SinewException) -> Unit): Result<T> = also { if (it is Result.Failure) action(it.error) }

/** Maps the data and keeps the message. */
public inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data), message)
    is Result.Failure -> this
}

/** The data, or throws the failure's [SinewException], so a use case can read results in a straight line. */
public fun <T> Result<T>.getOrThrow(): T = when (this) {
    is Result.Success -> data
    is Result.Failure -> throw error
}
