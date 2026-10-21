package com.srctool.sinew.testing

import okio.IOException

/** okio's: DataStore's error type on iOS. */
public actual fun platformStorageError(message: String): Throwable = IOException(message)
