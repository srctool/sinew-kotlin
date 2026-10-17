package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest

/** The contract on DataStore, in a temporary folder of the simulator. */
class IosKeyValueStoreTest : KeyValueStoreContract() {
    private val directory = NSTemporaryDirectory() + "sinew-prefs-" + NSUUID().UUIDString

    override suspend fun newStore(): KeyValueStore = DataStoreKeyValueStore("$directory/prefs.preferences_pb".toPath())
    override suspend fun reopen(): KeyValueStore = newStore()
    override suspend fun otherStore(): KeyValueStore = DataStoreKeyValueStore("$directory/other.preferences_pb".toPath())

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun deleteFiles() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }
}

actual val canCreateWithoutContext: Boolean = true

actual fun newPlatformContext(): PlatformContext = PlatformContext()
