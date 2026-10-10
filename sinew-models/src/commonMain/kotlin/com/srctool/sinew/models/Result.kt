package com.srctool.sinew.models

/**
 * What every guard, repository and use case returns. Sinew's own type rather than `kotlin.Result`: the failure is
 * always a typed [SinewException], and a success carries the backend's message, if it sent one. The helpers are named
 * like `kotlin.Result`'s, so code reads the same.
 */
public sealed interface Result<out T> {
    public data class Success<out T>(val data: T, val message: String = "") : Result<T>

    public data class Failure(val error: SinewException) : Result<Nothing>
}

public val Result<*>.isSuccess: Boolean get() = this is Result.Success

public val <T> Result<T>.dataOrNull: T? get() = (this as? Result.Success)?.data

public val Result<*>.errorOrNull: SinewException? get() = (this as? Result.Failure)?.error

public inline fun <T, R> Result<T>.fold(onSuccess: (T) -> R, onFailure: (SinewException) -> R): R = when (this) {
    is Result.Success -> onSuccess(data)
    is Result.Failure -> onFailure(error)
}

public inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> = also { if (it is Result.Success) action(it.data) }

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
