package com.srctool.sinew.testing

/**
 * The error a store on this platform throws when it can't read or write: `java.io.IOException` on Android and the
 * JVM, okio's `IOException` on iOS, a browser storage `DOMException` (`UnknownError`) on wasm. Through
 * `processStorageCall` it becomes a `StorageReadException` or `StorageWriteException` with the app's own module and
 * function, exactly as a real store's failure does. The fakes throw it; an app's own fake DAO can too.
 */
public expect fun platformStorageError(message: String = "Storage failed"): Throwable

/** A storage error saying the device is full: `processStorageCall` makes it a `StorageFullException`. */
public fun diskFullError(): Throwable = platformStorageError("No space left on device")
