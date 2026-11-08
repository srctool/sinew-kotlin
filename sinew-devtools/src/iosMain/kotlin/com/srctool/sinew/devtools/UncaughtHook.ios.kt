package com.srctool.sinew.devtools

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
internal actual fun installUncaughtHook(onError: (Throwable) -> Unit) {
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { error ->
        try {
            onError(error)
        } catch (_: Throwable) {
            // Recording must never stop the previous hook.
        }
        previous?.invoke(error)
    }
}
