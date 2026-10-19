package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import com.srctool.sinew.security.FieldCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The contract with Web Crypto and `localStorage` in the test browser. */
class WasmSecureStoreTest : SecureStoreContract() {
    private val cleared = mutableSetOf<String>()

    override suspend fun values(name: String): KeyValueStore =
        KeyValueStore.create(PlatformContext(), name).also { if (cleared.add(name)) it.clear() }

    override suspend fun cipher(alias: String): FieldCipher = FieldCipher.create(PlatformContext(), alias)

    @Test
    fun createKeepsItsValuesInTheNamespacesOwnStore() = runTest {
        val store = SecureStore.create(PlatformContext(), "web")
        store.clearUserData()
        store.write("token", "refresh-token")
        assertNotNull(rawItem("web_secure.token"))
        assertNull(rawItem("web.token"))
        assertEquals("refresh-token", SecureStore.create(PlatformContext(), "web").read("token"))
        store.clearUserData()
    }
}

private fun rawItem(key: String): String? = js("localStorage.getItem(key)")
