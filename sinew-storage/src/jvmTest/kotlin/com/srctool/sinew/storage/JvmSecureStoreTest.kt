package com.srctool.sinew.storage

import com.srctool.sinew.security.FieldCipher
import com.srctool.sinew.security.create
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.deleteExisting
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The contract with Tink and DataStore, in a temporary folder. */
class JvmSecureStoreTest : SecureStoreContract() {
    private val directory = Files.createTempDirectory("sinew-secure")

    override suspend fun values(name: String): KeyValueStore = KeyValueStore.create(directory, name)
    override suspend fun cipher(alias: String): FieldCipher = FieldCipher.create(directory, alias)

    @AfterTest
    fun deleteFiles() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun createKeepsValuesAndKeyInTheFolder() = runTest {
        SecureStore.create(directory, "app").write("token", "refresh-token")
        assertEquals("refresh-token", SecureStore.create(directory, "app").read("token"))
        assertEquals(setOf("app_secure.preferences_pb", "sinew.secure.app.keyset.json"), directory.toFile().list()!!.toSet())
    }

    @Test
    fun aLostKeyMakesASecretAbsentAndDeletesIt() = runTest {
        SecureStore.create(directory, "app").write("token", "refresh-token")
        directory.resolve("sinew.secure.app.keyset.json").deleteExisting()   // as a restore onto another machine
        assertNull(SecureStore.create(directory, "app").read("token"))       // a new store loads a new key
        assertNull(KeyValueStore.create(directory, "app_secure").getString("token"))
    }

    @Test
    fun aNamespaceThatIsntAPlainNameIsRefused() {
        for (namespace in listOf("", " ", "a/b", "a\\b")) {
            assertFailsWith<IllegalArgumentException>(namespace) { SecureStore.create(directory, namespace) }
            assertFailsWith<IllegalArgumentException>(namespace) { SecureStore.create(com.srctool.sinew.models.PlatformContext(), namespace) }
        }
    }
}
