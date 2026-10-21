package com.srctool.sinew.testing

internal actual fun droppedConnection(): Throwable =
    try {
        throwFetchFailure()
    } catch (e: Throwable) {
        e
    }

@OptIn(ExperimentalWasmJsInterop::class)
private fun throwFetchFailure(): Nothing = js("{ throw new TypeError('Failed to fetch'); }")
