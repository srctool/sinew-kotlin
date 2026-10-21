package com.srctool.sinew.testing

/** A browser storage `DOMException`, caught as the `JsException` a real `localStorage` failure is. */
public actual fun platformStorageError(message: String): Throwable =
    try {
        throwDomException(message)
    } catch (e: Throwable) {
        e
    }

@OptIn(ExperimentalWasmJsInterop::class)
private fun throwDomException(message: String): Nothing = js("{ throw new DOMException(message, 'UnknownError'); }")
