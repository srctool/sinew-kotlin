package com.srctool.sinew.security

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Values each target encrypted with the same test key: every target decrypts them all, so the layout is shared. */
class CrossTargetFixtureTest {
    @Test
    fun everyTargetsFixtureDecrypts() = runTest {
        assertTrue(Fixtures.values.map { it.producedBy }.toSet().containsAll(listOf("tink", "cryptokit", "webcrypto")))
        val cipher = fixedKeyCipher(Fixtures.KEY, Fixtures.KEY_ID)
        for (fixture in Fixtures.values) {
            assertEquals(fixture.plain, cipher.decrypt(fixture.cipher, fixture.context), fixture.producedBy)
            assertFailsWith<DecryptionFailed>(fixture.producedBy) { cipher.decrypt(fixture.cipher, fixture.context + "!") }
        }
    }

    @Test
    fun aValueEncryptedHereCarriesTheFixtureKeyId() = runTest {
        val cipher = fixedKeyCipher(Fixtures.KEY, Fixtures.KEY_ID)
        val value = cipher.encrypt("Alice Liddell", "customers.displayName:42")
        assertEquals(Fixtures.KEY_ID, CipherLayout.keyId(Base64.decode(value)))
        assertEquals("Alice Liddell", cipher.decrypt(value, "customers.displayName:42"))
    }
}

/**
 * The test key, its id, and what each target's library produced with it (`aValueEncryptedHereCarriesTheFixtureKeyId`'s
 * value, captured once per target).
 */
object Fixtures {
    val KEY: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }
    const val KEY_ID: Int = 0x2A5F1C07

    class Fixture(val producedBy: String, val context: String, val plain: String, val cipher: String)

    private const val CONTEXT = "customers.displayName:42"
    private const val PLAIN = "Alice Liddell"

    val values: List<Fixture> = listOf(
        Fixture("tink", CONTEXT, PLAIN, "ASpfHAcQPsYltGPhvCsrCl3urLaWqtwKoK2RanipnLLdg6sMJs6QTrFZtX2yZA=="),
        Fixture("tink-android", CONTEXT, PLAIN, "ASpfHAdbeYXYNPB5CXXihV07wWVBKxgGPd7gubrqt95F2RWGtmUffdm+y7oZOQ=="),
        Fixture("cryptokit", CONTEXT, PLAIN, "ASpfHAfET1L4ee0DQgysR9FAJoT22FGCV3ylj+HX3xUy1u/8jUxKlJpNkk7scg=="),
        Fixture("webcrypto", CONTEXT, PLAIN, "ASpfHAf3t12g9cCkTkjnMNkQ29RJWgnavJxU1adEh/b/U/V9oJEEXTQpgXswuQ=="),
    )
}
