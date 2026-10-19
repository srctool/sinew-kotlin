package com.srctool.sinew.storage

import com.srctool.sinew.security.DecryptionFailed
import com.srctool.sinew.security.FieldCipher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The suite every target's [SecureStore] passes. A target supplies the value store and the cipher for a store, new and
 * empty for each test; the same name or alias within a test means the same storage.
 */
abstract class SecureStoreContract {
    abstract suspend fun values(name: String): KeyValueStore

    abstract suspend fun cipher(alias: String): FieldCipher

    /** Whether [cipher] really encrypts (a target without key storage uses [FakeCipher]). */
    open val realCipher: Boolean = true

    private suspend fun store(namespace: String = "test"): SecureStore =
        EncryptedSecureStore(namespace, values("${namespace}_secure"), cipher("sinew.secure.$namespace"))

    @Test
    fun aSecretRoundTrips() = runTest {
        val store = store()
        for (secret in listOf("", "refresh-token", "Ä 😀 日本語", "x".repeat(4_000))) {
            store.write("session.refreshToken", secret)
            assertEquals(secret, store.read("session.refreshToken"))
        }
    }

    @Test
    fun aMissingKeyIsAbsent() = runTest {
        assertNull(store().read("missing"))
    }

    @Test
    fun deleteRemovesOneSecret() = runTest {
        val store = store()
        store.write("a", "1")
        store.write("b", "2")
        store.delete("a")
        store.delete("never-written")
        assertNull(store.read("a"))
        assertEquals("2", store.read("b"))
    }

    @Test
    fun clearUserDataEmptiesThisNamespaceOnly() = runTest {
        val store = store("one")
        val other = store("two")
        store.write("a", "1")
        store.write("b", "2")
        other.write("a", "kept")
        store.clearUserData()
        assertNull(store.read("a"))
        assertNull(store.read("b"))
        assertEquals("kept", other.read("a"))
    }

    @Test
    fun secretsPersistForTheSameNamespace() = runTest {
        store().write("token", "refresh-token")
        assertEquals("refresh-token", store().read("token"))
    }

    @Test
    fun theStoredValueIsNotThePlainSecret() = runTest {
        if (!realCipher) return@runTest
        store().write("token", "refresh-token")
        val stored = values("test_secure").getString("token")!!
        assertFalse("refresh-token" in stored)
    }

    @Test
    fun aValueThatCantBeDecryptedIsAbsentAndDeleted() = runTest {
        val store = store()
        values("test_secure").setString("token", "not a ciphertext")
        assertNull(store.read("token"))
        assertNull(values("test_secure").getString("token"))
    }

    @Test
    fun aValueCopiedToAnotherKeyDoesntDecrypt() = runTest {
        val store = store()
        store.write("a", "secret")
        values("test_secure").setString("b", values("test_secure").getString("a")!!)
        assertNull(store.read("b"))
        assertEquals("secret", store.read("a"))
    }

    @Test
    fun itCanBeInspected() = runTest {
        val store = store("inspected")
        store.write("b", "2")
        store.write("a", "1")
        assertEquals("inspected", store.name)
        assertEquals(listOf("a", "b"), store.keys())
        assertEquals("1", store.read("a"))
    }

    @Test
    fun concurrentWritesAllLand() = runTest {
        val store = store()
        List(30) { i -> async { store.write("key$i", "value $i") } }.awaitAll()
        for (i in 0 until 30) assertEquals("value $i", store.read("key$i"))
    }
}

/**
 * A reversible stand-in for [FieldCipher], with no cryptography: `fake:<context>:<plain>`. A value with another
 * context or another shape throws [DecryptionFailed], as the real cipher does.
 */
class FakeCipher : FieldCipher {
    override suspend fun encrypt(plain: String, context: String): String = "fake:${context.length}:$context:$plain"

    override suspend fun decrypt(cipher: String, context: String): String {
        val prefix = "fake:${context.length}:$context:"
        if (!cipher.startsWith(prefix)) throw DecryptionFailed()
        return cipher.removePrefix(prefix)
    }

    override suspend fun rotateKey() {}
}
