package com.srctool.sinew.security

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The suite every target's [FieldCipher] passes. A target subclasses it with its real key storage, emptied for each
 * test.
 */
abstract class FieldCipherContract {
    /** A cipher over empty key storage. */
    abstract suspend fun newCipher(): FieldCipher

    /** Another cipher over the storage of the last [newCipher], as after an app restart. */
    abstract suspend fun reopen(): FieldCipher

    private fun bytes(cipher: String) = Base64.decode(cipher)

    @Test
    fun aValueRoundTrips() = runTest {
        val cipher = newCipher()
        for (plain in listOf("", "Alice", "Ä 😀 日本語", "x".repeat(10_000))) {
            for (context in listOf("", "customers.displayName:42")) {
                assertEquals(plain, cipher.decrypt(cipher.encrypt(plain, context), context))
            }
        }
    }

    @Test
    fun twoEncryptionsDifferWithFreshNonces() = runTest {
        val cipher = newCipher()
        val values = List(50) { bytes(cipher.encrypt("Alice", "ctx")) }
        val nonces = values.map { it.copyOfRange(CipherLayout.HEADER_SIZE, CipherLayout.HEADER_SIZE + CipherLayout.NONCE_SIZE).toList() }
        assertEquals(50, nonces.toSet().size)
        assertEquals(50, values.map { it.toList() }.toSet().size)
    }

    @Test
    fun everyFlippedByteFails() = runTest {
        val cipher = newCipher()
        val sealed = bytes(cipher.encrypt("Alice", "ctx"))
        for (i in sealed.indices) {
            val changed = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<DecryptionFailed>("byte $i") { cipher.decrypt(Base64.encode(changed), "ctx") }
        }
    }

    @Test
    fun aWrongContextFails() = runTest {
        val cipher = newCipher()
        val value = cipher.encrypt("Alice", "customers.displayName:1")
        assertFailsWith<DecryptionFailed> { cipher.decrypt(value, "customers.displayName:2") }
        assertFailsWith<DecryptionFailed> { cipher.decrypt(value) }
        assertFailsWith<DecryptionFailed> { cipher.decrypt(cipher.encrypt("Alice"), "customers.displayName:1") }
    }

    @Test
    fun theHeaderCarriesTheKeyId() = runTest {
        val cipher = newCipher()
        val first = bytes(cipher.encrypt("Alice"))
        val second = bytes(cipher.encrypt("Bob"))
        assertEquals(CipherLayout.VERSION, first[0])
        assertEquals(CipherLayout.MIN_SIZE + "Alice".length, first.size)
        assertEquals(CipherLayout.keyId(first), CipherLayout.keyId(second))
    }

    @Test
    fun aValueFromBeforeARotationStillDecrypts() = runTest {
        val cipher = newCipher()
        val old = cipher.encrypt("Alice", "ctx")
        cipher.rotateKey()
        val new = cipher.encrypt("Bob", "ctx")
        assertNotEquals(CipherLayout.keyId(bytes(old)), CipherLayout.keyId(bytes(new)))
        assertEquals("Alice", cipher.decrypt(old, "ctx"))
        assertEquals("Bob", cipher.decrypt(new, "ctx"))
    }

    @Test
    fun aRotationUsesNewKeyMaterial() = runTest {
        val cipher = newCipher()
        val old = bytes(cipher.encrypt("Alice"))
        cipher.rotateKey()
        val newId = CipherLayout.keyId(bytes(cipher.encrypt("Bob")))
        val relabelled = CipherLayout.header(newId) + old.copyOfRange(CipherLayout.HEADER_SIZE, old.size)
        assertFailsWith<DecryptionFailed> { cipher.decrypt(Base64.encode(relabelled)) }
    }

    @Test
    fun keysAndRotationsPersist() = runTest {
        val cipher = newCipher()
        val old = cipher.encrypt("Alice")
        cipher.rotateKey()
        val new = cipher.encrypt("Bob")
        val restarted = reopen()
        assertEquals("Alice", restarted.decrypt(old))
        assertEquals("Bob", restarted.decrypt(new))
        assertEquals(CipherLayout.keyId(bytes(new)), CipherLayout.keyId(bytes(restarted.encrypt("Carol"))))
    }

    @Test
    fun concurrentFirstUseCreatesOneKey() = runTest {
        val cipher = newCipher()
        val values = List(20) { i -> async { cipher.encrypt("value $i") } }.awaitAll()
        assertEquals(1, values.map { CipherLayout.keyId(bytes(it)) }.toSet().size)
        val restarted = reopen()
        values.forEachIndexed { i, value -> assertEquals("value $i", restarted.decrypt(value)) }
    }

    @Test
    fun aMalformedValueFails() = runTest {
        val cipher = newCipher()
        val valid = bytes(cipher.encrypt("Alice"))
        val malformed = listOf(
            "",
            "not base64!",
            Base64.encode(valid.copyOf(CipherLayout.MIN_SIZE - 1)),
            Base64.encode(valid.copyOf().also { it[0] = 0x00 }),
        )
        for (value in malformed) assertFailsWith<DecryptionFailed>(value) { cipher.decrypt(value) }
    }

    @Test
    fun anotherKeysValueFails() = runTest {
        val cipher = newCipher()
        val foreign = fixedKeyCipher(Fixtures.KEY, Fixtures.KEY_ID).encrypt("Alice")
        assertFailsWith<DecryptionFailed> { cipher.decrypt(foreign) }
    }
}
