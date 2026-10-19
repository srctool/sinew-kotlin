package com.srctool.sinew.storage

import okio.IOException

internal actual fun isPlatformStorageFull(e: Throwable): Boolean = false

internal actual fun isStorageError(e: Throwable): Boolean = e is IOException
