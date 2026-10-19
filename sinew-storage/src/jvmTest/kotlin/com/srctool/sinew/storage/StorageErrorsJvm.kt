package com.srctool.sinew.storage

import androidx.datastore.core.CorruptionException
import com.srctool.sinew.exception.LocalStorageCorruptionException
import com.srctool.sinew.models.Result
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertIs

actual fun diskFullErrors(): List<Throwable> = listOf(
    IOException("No space left on device"),                                     // FileOutputStream.write
    FileSystemException("/data/prefs.preferences_pb", null, "No space left on device"),
    SQLException("[SQLITE_FULL] Insertion failed because database is full (database or disk is full)"),
)

actual fun otherStorageErrors(): List<Throwable> = listOf(
    IOException("Permission denied"),
    FileSystemException("/data/prefs.preferences_pb", null, "Read-only file system"),
    SQLException("[SQLITE_BUSY] The database file is locked"),
)

actual fun corruptionErrors(): List<Throwable> = listOf(CorruptionException("Unable to parse preferences proto."))

/** A real corrupted DataStore file, read through a store and the guard. */
class JvmStorageCorruptionTest {
    @Test
    fun aCorruptedFileIsLocalStorageCorruption() = runTest {
        val directory = Files.createTempDirectory("sinew-corrupt")
        Files.write(directory.resolve("prefs.preferences_pb"), byteArrayOf(0x0A, 0x7F, 0x13, 0x00, 0x42))
        val result = processStorageCall("PRF", "GTH", StorageAccess.Read) { KeyValueStore.create(directory, "prefs").getString("theme") }
        assertIs<LocalStorageCorruptionException>(assertIs<Result.Failure>(result).error)
        directory.toFile().deleteRecursively()
    }
}
