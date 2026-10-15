package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The contract on Web Crypto, with the keyring in the test browser's IndexedDB. */
class WasmFieldCipherTest : FieldCipherContract() {
    private val alias = "sinew.test.field"
    private val store = IndexedDbRingStore(alias)

    override suspend fun newCipher(): FieldCipher {
        store.delete()
        return reopen()
    }

    override suspend fun reopen(): FieldCipher = FieldCipher.create(PlatformContext(), alias)

    @Test
    fun theKeyIsStoredAndCantBeExported() = runTest {
        newCipher().encrypt("Alice")
        val ring = assertNotNull(store.load())
        assertEquals(1, ring.keys.size)
        assertEquals(false, isExtractable(ring.keys.getValue(ring.primary)))
    }

    @Test
    fun aRecordThatIsntAKeyringIsReplaced() = runTest {
        newCipher()
        ringSave(alias, ringRecord(5)).await<JsAny?>()                    // a primary with no keys
        assertNull(store.load())
        val cipher = reopen()
        assertEquals("Alice", cipher.decrypt(cipher.encrypt("Alice")))
        assertNotNull(store.load())
    }

    @Test
    fun aDeletedKeyringStartsAFreshOneAndOldValuesFail() = runTest {
        val old = newCipher().encrypt("Alice")
        store.delete()
        assertFailsWith<DecryptionFailed> { reopen().decrypt(old) }
    }
}

private fun isExtractable(key: JsAny): Boolean = js("key.extractable")
