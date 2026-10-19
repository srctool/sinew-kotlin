package com.srctool.sinew.storage

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.StorageReadException
import com.srctool.sinew.exception.StorageWriteException
import com.srctool.sinew.models.Result
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/** Android's own SQLite errors, which exist only on a device, through the guard. */
class AndroidStorageGuardDeviceTest {
    private suspend fun fail(error: Throwable, access: StorageAccess) =
        assertIs<Result.Failure>(processStorageCall<Unit>("ORD", "GCO", access) { throw error }).error

    @Test
    fun sqliteFullIsStorageFull() = runTest {
        assertIs<StorageFullException>(fail(SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)"), StorageAccess.Write))
        assertIs<StorageFullException>(fail(SQLiteFullException(), StorageAccess.Write))     // no message: by type
    }

    @Test
    fun anotherSqliteErrorIsAReadOrWriteFailure() = runTest {
        assertIs<StorageReadException>(fail(SQLiteDatabaseLockedException("database is locked"), StorageAccess.Read))
        assertIs<StorageWriteException>(fail(SQLiteDiskIOException("disk I/O error"), StorageAccess.Write))
    }
}
