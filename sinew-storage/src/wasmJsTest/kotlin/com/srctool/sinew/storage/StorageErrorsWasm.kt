package com.srctool.sinew.storage

import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.models.PlatformContext
import com.srctool.sinew.models.Result
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

actual fun diskFullErrors(): List<Throwable> = listOf(jsError("QuotaExceededError"))

actual fun otherStorageErrors(): List<Throwable> = listOf(jsError("SecurityError"), jsError("InvalidStateError"))

actual fun corruptionErrors(): List<Throwable> = emptyList()

/** A JavaScript `DOMException`-like error named [name], thrown and caught as Kotlin sees it. */
private fun jsError(name: String): Throwable = try {
    throwNamed(name)
    error("unreachable")
} catch (e: Throwable) {
    e
}

private fun throwNamed(name: String): Unit = js("{ const e = new Error(name); e.name = name; throw e; }")

class WasmStorageErrorsTest {
    @Test
    fun aFullLocalStorageIsStorageFull() = runTest {
        val store = KeyValueStore.create(PlatformContext(), "quota")
        val chunk = "x".repeat(512 * 1024)
        val result = processStorageCall("PRF", "STH", StorageAccess.Write) {
            for (i in 0 until 64) store.setString("chunk$i", chunk)              // past the browser's ~5 MB per origin
        }
        store.clear()
        assertIs<StorageFullException>(assertIs<Result.Failure>(result).error)
    }

    @Test
    fun aPlainJavaScriptErrorIsABugNotStorage() = runTest {
        val result = processStorageCall<Unit>("PRF", "GTH", StorageAccess.Read) { throw jsError("TypeError") }
        assertIs<UnexpectedException>(assertIs<Result.Failure>(result).error)
    }
}
