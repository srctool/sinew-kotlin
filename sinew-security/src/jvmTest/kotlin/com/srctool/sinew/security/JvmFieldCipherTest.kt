package com.srctool.sinew.security

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The contract on Tink, with a real keyset file in a temporary folder. */
class JvmFieldCipherTest : FieldCipherContract() {
    private val directory: Path = Files.createTempDirectory("sinew-cipher")

    override suspend fun newCipher(): FieldCipher = FieldCipher.create(directory, "test")

    override suspend fun reopen(): FieldCipher = FieldCipher.create(directory, "test")

    @AfterTest
    fun deleteFiles() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun theKeysetFileIsTheOwnersOnly() = runTest {
        newCipher().encrypt("Alice")
        val file = directory.resolve("test.keyset.json")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertTrue(file.readText().contains("AesGcmKey"))
        assertEquals(listOf("test.keyset.json"), directory.toFile().list()!!.toList())   // no temporary file left
    }

    @Test
    fun anUnreadableKeysetStartsAFreshOneAndOldValuesFail() = runTest {
        val old = newCipher().encrypt("Alice")
        directory.resolve("test.keyset.json").writeText("not a keyset")
        val restarted = reopen()
        assertFailsWith<DecryptionFailed> { restarted.decrypt(old) }
        assertEquals("Bob", restarted.decrypt(restarted.encrypt("Bob")))
    }

    @Test
    fun aliasesHaveTheirOwnKeys() = runTest {
        val value = FieldCipher.create(directory, "a").encrypt("Alice")
        assertFailsWith<DecryptionFailed> { FieldCipher.create(directory, "b").decrypt(value) }
    }

    @Test
    fun aBlankAliasIsRefused() {
        assertFailsWith<IllegalArgumentException> { FieldCipher.create(directory, " ") }
    }
}
