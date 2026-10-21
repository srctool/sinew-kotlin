package com.srctool.sinew.testing

import java.io.IOException

public actual fun platformStorageError(message: String): Throwable = IOException(message)
