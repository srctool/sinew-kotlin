package com.srctool.sinew.storage

import com.srctool.sinew.security.FieldCipher
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest

/**
 * The contract on DataStore with [FakeCipher]: a bare test binary gets no Keychain. `IosSecureStoreAppHostTest` runs
 * the real store as an app.
 */
class IosSecureStoreTest : SecureStoreContract() {
    private val directory = NSTemporaryDirectory() + "sinew-secure-" + NSUUID().UUIDString

    override val realCipher: Boolean = false
    override suspend fun values(name: String): KeyValueStore = DataStoreKeyValueStore("$directory/$name.preferences_pb".toPath())
    override suspend fun cipher(alias: String): FieldCipher = FakeCipher()

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun deleteFiles() {
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }
}
