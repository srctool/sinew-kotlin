package com.srctool.sinew.devtools

internal actual fun installUncaughtHook(onError: (Throwable) -> Unit) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            onError(error)
        } catch (_: Throwable) {
            // Recording must never stop the previous handler, which may report the crash or end the process.
        }
        previous?.uncaughtException(thread, error)
    }
}
