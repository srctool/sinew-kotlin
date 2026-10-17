package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import java.nio.file.Files
import kotlin.test.AfterTest

/** The contract on DataStore, in a temporary folder. */
class JvmKeyValueStoreTest : KeyValueStoreContract() {
    private val directory = Files.createTempDirectory("sinew-prefs")
    private var name = "prefs"

    override suspend fun newStore(): KeyValueStore = KeyValueStore.create(directory, name)
    override suspend fun reopen(): KeyValueStore = KeyValueStore.create(directory, name)
    override suspend fun otherStore(): KeyValueStore = KeyValueStore.create(directory, "other")

    @AfterTest
    fun deleteFiles() {
        directory.toFile().deleteRecursively()
    }
}

actual val canCreateWithoutContext: Boolean = true

actual fun newPlatformContext(): PlatformContext = PlatformContext()
