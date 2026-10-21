package com.srctool.sinew.testing

import com.srctool.sinew.security.DecryptionFailed
import com.srctool.sinew.security.FieldCipher

/**
 * A [FieldCipher] **with no cryptography**: it stores a readable marked form, `enc(Alice)`, or `enc[context](Alice)`
 * with a context, so a test can see which fields were encrypted and with what context. Decrypting checks the mark and
 * the context, as the real cipher does, and fails with [DecryptionFailed] otherwise.
 *
 * For code that only stores and loads values. Never use it to prove encryption: crypto tests use the real cipher.
 */
public class FakeFieldCipher : FieldCipher {
    private var failNext = false

    /** How many times [rotateKey] was called. */
    public var rotations: Int = 0
        private set

    /** Makes the next decryption fail with [DecryptionFailed], once. */
    public fun failNextDecrypt() {
        failNext = true
    }

    override suspend fun encrypt(plain: String, context: String): String = "${prefix(context)}$plain)"

    override suspend fun decrypt(cipher: String, context: String): String {
        if (failNext) {
            failNext = false
            throw DecryptionFailed()
        }
        val prefix = prefix(context)
        if (!cipher.startsWith(prefix) || !cipher.endsWith(")")) throw DecryptionFailed()
        return cipher.substring(prefix.length, cipher.length - 1)
    }

    override suspend fun rotateKey() {
        rotations++
    }

    private fun prefix(context: String) = if (context.isEmpty()) "enc(" else "enc[$context]("
}
