package com.srctool.sinew.security

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The contract on CryptoKit, with the keyring in memory: Kotlin/Native runs iOS tests as a bare executable on the
 * simulator, which gets no Keychain (`errSecNotAvailable`), and the simulator won't launch one signed with a
 * keychain entitlement.
 */
class IosFieldCipherTest : FieldCipherContract() {
    private var store = MemoryRingStore<ByteArray>()

    override suspend fun newCipher(): FieldCipher {
        store = MemoryRingStore()
        return reopen()
    }

    override suspend fun reopen(): FieldCipher = LayoutFieldCipher { RingKeys.open(CryptoKitAesGcm, store) }

    @Test
    fun theFirstUseSavesOneKey() = runTest {
        val cipher = newCipher()
        repeat(3) { cipher.decrypt(cipher.encrypt("Alice")) }
        val ring = assertNotNull(store.ring)
        assertEquals(1, store.loads, "the keyring is loaded once, then kept")
        assertEquals(1, store.saves)
        assertEquals(1, ring.keys.size)
        assertEquals(32, ring.keys.getValue(ring.primary).size)
    }

    @Test
    fun aKeyringRoundTripsThroughItsBytes() {
        val ring = Keyring(-5, mapOf(-5 to ByteArray(32) { 1 }, 0x7fffffff to ByteArray(32) { 2 }))
        val decoded = assertNotNull(decodeRing(encodeRing(ring)))
        assertEquals(ring.primary, decoded.primary)
        assertEquals(ring.keys.keys, decoded.keys.keys)
        ring.keys.forEach { (id, key) -> assertContentEquals(key, decoded.keys.getValue(id)) }
    }

    @Test
    fun bytesThatArentAKeyringDecodeToNull() {
        val valid = encodeRing(Keyring(1, mapOf(1 to ByteArray(32))))
        assertNull(decodeRing(ByteArray(0)))
        assertNull(decodeRing(valid.copyOf(valid.size - 1)))
        assertNull(decodeRing(valid.copyOf().also { it[3] = 2 }))       // the primary isn't among the keys
    }
}
