package com.srctool.sinew.storage

import androidx.datastore.core.CorruptionException
import com.srctool.sinew.exception.LocalStorageCorruptionException
import com.srctool.sinew.models.Result
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs

// The host has no android.database classes; SQLite's errors are in the device test.
actual fun diskFullErrors(): List<Throwable> = listOf(IOException("write failed: ENOSPC (No space left on device)"))

actual fun otherStorageErrors(): List<Throwable> = listOf(IOException("open failed: EACCES (Permission denied)"))

actual fun corruptionErrors(): List<Throwable> = listOf(CorruptionException("Unable to parse preferences proto."))

class AndroidHostStorageCorruptionTest {
    @Test
    fun aCorruptedFileIsLocalStorageCorruption() = runTest {
        val directory = Files.createTempDirectory("sinew-corrupt").toFile()
        val file = directory.resolve("prefs.preferences_pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F, 0x13, 0x00, 0x42)) }
        val result = processStorageCall("PRF", "GTH", StorageAccess.Read) { DataStoreKeyValueStore(file.toOkioPath()).getString("theme") }
        assertIs<LocalStorageCorruptionException>(assertIs<Result.Failure>(result).error)
        directory.deleteRecursively()
    }
}
