package com.srctool.sinew.storage

import java.io.IOException
import java.sql.SQLException

internal actual fun isPlatformStorageFull(e: Throwable): Boolean = false

internal actual fun isStorageError(e: Throwable): Boolean = e is IOException || e is SQLException
