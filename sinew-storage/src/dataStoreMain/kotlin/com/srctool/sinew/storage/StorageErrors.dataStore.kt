package com.srctool.sinew.storage

import androidx.datastore.core.CorruptionException

internal actual fun isPlatformCorruption(e: Throwable): Boolean = e is CorruptionException
