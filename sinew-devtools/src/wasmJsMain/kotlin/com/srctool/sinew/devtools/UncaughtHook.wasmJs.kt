@file:OptIn(ExperimentalWasmJsInterop::class)

package com.srctool.sinew.devtools

/** An error the browser reported to the window: its message and stack, as the browser gave them. */
internal class UncaughtBrowserError(message: String) : Throwable(message)

/** The window's `error` and `unhandledrejection` events. Listeners stack, so earlier ones still run. */
internal actual fun installUncaughtHook(onError: (Throwable) -> Unit) {
    addErrorListeners { message ->
        try {
            onError(UncaughtBrowserError(message))
        } catch (_: Throwable) {
            // Recording must never break the page's own listeners.
        }
    }
}

private fun addErrorListeners(handler: (String) -> Unit): Unit = js(
    """{
    const describe = (value) => value && value.stack ? String(value.stack) : String(value);
    window.addEventListener('error', (e) => handler(e.error ? describe(e.error) : String(e.message)));
    window.addEventListener('unhandledrejection', (e) => handler('Unhandled promise rejection: ' + describe(e.reason)));
}""",
)
