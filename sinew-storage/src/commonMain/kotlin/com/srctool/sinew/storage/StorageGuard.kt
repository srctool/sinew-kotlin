package com.srctool.sinew.storage

import com.srctool.sinew.exception.ExceptionHandler
import com.srctool.sinew.exception.LocalStorageCorruptionException
import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.StorageReadException
import com.srctool.sinew.exception.StorageWriteException
import com.srctool.sinew.exception.envelopeDataMissing
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.security.DecryptionFailed
import kotlinx.serialization.SerializationException

/** Which kind of storage call failed: a failed read can be retried freely, a failed write may need the user. */
public enum class StorageAccess { Read, Write }

/**
 * The source guard for storage, the counterpart of `processApiCall`: a store, a DAO or a local source called in
 * [block] fails with a typed exception (in the repository layer) instead of crashing. See [storageHandlers].
 */
public suspend fun <T> processStorageCall(
    module: String,
    function: String,
    access: StorageAccess,
    block: suspend () -> T,
): Result<T> = processCall(module, function, ExceptionLayer.Repository, storageHandlers(module, function, access), block)

/**
 * The storage handler table, public for apps composing their own guard. Each row looks through a failure's causes,
 * so a storage library wrapping its error doesn't change the answer.
 *
 * | Matches | Produces |
 * |---|---|
 * | `DecryptionFailed`, DataStore's `CorruptionException`, a `SerializationException` on stored data | `LocalStorageCorruptionException` |
 * | the device out of space: `ENOSPC` ("No space left on device"), SQLite's "database or disk is full", Android's `SQLiteFullException`, the browser's `QuotaExceededError` | `StorageFullException` |
 * | any other storage error: an I/O error, a SQLite error, a browser storage error | `StorageReadException` / `StorageWriteException`, by [access] |
 * | `EnvelopeDataMissing` | `ParseException` |
 *
 * Anything else, a bug in the block, is an `UnexpectedException`, reported.
 */
public fun storageHandlers(module: String, function: String, access: StorageAccess): List<ExceptionHandler> {
    val layer = ExceptionLayer.Repository
    return listOf(
        ExceptionHandler({ it.causes().any(::isCorruption) }) { LocalStorageCorruptionException(module, layer, function, cause = it) },
        ExceptionHandler({ it.causes().any(::isStorageFull) }) { StorageFullException(module, layer, function, cause = it) },
        ExceptionHandler({ it.causes().any(::isStorageError) }) {
            when (access) {
                StorageAccess.Read -> StorageReadException(module, layer, function, cause = it)
                StorageAccess.Write -> StorageWriteException(module, layer, function, cause = it)
            }
        },
        envelopeDataMissing(module, function, layer),
    )
}

/** [this], its causes and their suppressed exceptions, at most a few deep. */
internal fun Throwable.causes(): Sequence<Throwable> =
    generateSequence(this) { it.cause }.take(8).flatMap { sequenceOf(it) + it.suppressedExceptions.asSequence() }

internal fun isCorruption(e: Throwable): Boolean = e is DecryptionFailed || e is SerializationException || isPlatformCorruption(e)

private val outOfSpace = listOf("no space left on device", "enospc", "database or disk is full", "sqlite_full")

internal fun isStorageFull(e: Throwable): Boolean =
    e.message?.lowercase()?.let { message -> outOfSpace.any { it in message } } == true || isPlatformStorageFull(e)

/** The storage library's own corruption type: DataStore's `CorruptionException` where there's DataStore. */
internal expect fun isPlatformCorruption(e: Throwable): Boolean

/** The platform's own out-of-space types: Android's `SQLiteFullException`, the browser's `QuotaExceededError`. */
internal expect fun isPlatformStorageFull(e: Throwable): Boolean

/** A storage error, as opposed to a bug: an I/O error, a SQLite error, a browser storage error. */
internal expect fun isStorageError(e: Throwable): Boolean
