@file:OptIn(ExperimentalWasmJsInterop::class)

package com.srctool.sinew.devtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UncaughtHookWasmTest {
    @Test
    fun anUnhandledRejectionIsRecorded() {
        val seen = mutableListOf<Throwable>()
        installUncaughtHook { seen += it }
        dispatchUnhandledRejection("lost promise")
        val error = seen.single()
        assertEquals("UncaughtBrowserError", error::class.simpleName)
        assertTrue("Unhandled promise rejection: " in error.message.orEmpty(), error.message)
        assertTrue("lost promise" in error.message.orEmpty(), error.message)
    }
}

private fun dispatchUnhandledRejection(reason: String): Unit = js(
    """{
    const promise = Promise.reject(reason);
    promise.catch(() => {});
    window.dispatchEvent(new PromiseRejectionEvent('unhandledrejection', { promise, reason }));
}""",
)
