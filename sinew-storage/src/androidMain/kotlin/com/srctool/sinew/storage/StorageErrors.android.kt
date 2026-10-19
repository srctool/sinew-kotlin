package com.srctool.sinew.storage

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import java.io.IOException

internal actual fun isPlatformStorageFull(e: Throwable): Boolean = e is SQLiteFullException

internal actual fun isStorageError(e: Throwable): Boolean = e is IOException || e is SQLiteException
