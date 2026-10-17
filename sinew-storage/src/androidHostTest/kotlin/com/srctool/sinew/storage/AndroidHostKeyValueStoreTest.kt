package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest

/** The contract on the DataStore code Android ships, in a temporary folder: the host has no `Context`. */
class AndroidHostKeyValueStoreTest : KeyValueStoreContract() {
    private val directory = Files.createTempDirectory("sinew-prefs").toFile()

    override suspend fun newStore(): KeyValueStore = DataStoreKeyValueStore(directory.resolve("prefs.preferences_pb").toOkioPath())
    override suspend fun reopen(): KeyValueStore = newStore()
    override suspend fun otherStore(): KeyValueStore = DataStoreKeyValueStore(directory.resolve("other.preferences_pb").toOkioPath())

    @AfterTest
    fun deleteFiles() {
        directory.deleteRecursively()
    }
}

actual val canCreateWithoutContext: Boolean = false

actual fun newPlatformContext(): PlatformContext = error("no Context on the host")
