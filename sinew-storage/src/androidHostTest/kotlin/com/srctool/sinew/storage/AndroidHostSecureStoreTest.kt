package com.srctool.sinew.storage

import com.srctool.sinew.security.FieldCipher
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.AfterTest

/** The contract on Android's DataStore code with [FakeCipher]: the host has no Keystore (the device test has). */
class AndroidHostSecureStoreTest : SecureStoreContract() {
    private val directory = Files.createTempDirectory("sinew-secure").toFile()
    private val ciphers = mutableMapOf<String, FieldCipher>()

    override val realCipher: Boolean = false
    override suspend fun values(name: String): KeyValueStore = DataStoreKeyValueStore(directory.resolve("$name.preferences_pb").toOkioPath())
    override suspend fun cipher(alias: String): FieldCipher = ciphers.getOrPut(alias) { FakeCipher() }

    @AfterTest
    fun deleteFiles() {
        directory.deleteRecursively()
    }
}
