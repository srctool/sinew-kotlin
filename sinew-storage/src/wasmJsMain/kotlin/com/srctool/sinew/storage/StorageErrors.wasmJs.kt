package com.srctool.sinew.storage

internal actual fun isPlatformCorruption(e: Throwable): Boolean = false

internal actual fun isPlatformStorageFull(e: Throwable): Boolean = jsErrorName(e) == "QuotaExceededError"

/** A browser storage error: a `DOMException` from `localStorage` or IndexedDB, as opposed to a bug's `TypeError`. */
internal actual fun isStorageError(e: Throwable): Boolean = jsErrorName(e) in storageErrorNames

private val storageErrorNames = setOf("QuotaExceededError", "SecurityError", "InvalidStateError", "UnknownError", "NotReadableError", "DataError")

/** The `name` of the JavaScript error [e] carries, if it's one. */
@OptIn(ExperimentalWasmJsInterop::class)
internal fun jsErrorName(e: Throwable): String? = (e as? JsException)?.thrownValue?.let(::errorName)

private fun errorName(value: JsAny): String? = js("(value && typeof value.name === 'string') ? value.name : null")
