package com.srctool.sinew.storage

import androidx.datastore.core.CorruptionException
import com.srctool.sinew.exception.LocalStorageCorruptionException
import com.srctool.sinew.models.Result
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertIs

/** okio, under DataStore on iOS, turns `errno` into an `IOException` with its `strerror` text. */
actual fun diskFullErrors(): List<Throwable> = listOf(IOException("No space left on device"))

actual fun otherStorageErrors(): List<Throwable> = listOf(IOException("Permission denied"))

actual fun corruptionErrors(): List<Throwable> = listOf(CorruptionException("Unable to parse preferences proto."))

class IosStorageCorruptionTest {
    @Test
    fun aCorruptedFileIsLocalStorageCorruption() = runTest {
        val directory = (NSTemporaryDirectory() + "sinew-corrupt-" + NSUUID().UUIDString).toPath()
        FileSystem.SYSTEM.createDirectories(directory)
        val file = directory / "prefs.preferences_pb"
        FileSystem.SYSTEM.write(file) { write(byteArrayOf(0x0A, 0x7F, 0x13, 0x00, 0x42)) }
        val result = processStorageCall("PRF", "GTH", StorageAccess.Read) { DataStoreKeyValueStore(file).getString("theme") }
        assertIs<LocalStorageCorruptionException>(assertIs<Result.Failure>(result).error)
        FileSystem.SYSTEM.deleteRecursively(directory)
    }
}
