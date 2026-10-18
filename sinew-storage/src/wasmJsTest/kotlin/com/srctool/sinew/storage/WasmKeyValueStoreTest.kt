package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The contract on the test browser's real `localStorage`. */
class WasmKeyValueStoreTest : KeyValueStoreContract() {
    override suspend fun newStore(): KeyValueStore = KeyValueStore.create(PlatformContext(), "test").also { it.clear() }
    override suspend fun reopen(): KeyValueStore = KeyValueStore.create(PlatformContext(), "test")
    override suspend fun otherStore(): KeyValueStore = KeyValueStore.create(PlatformContext(), "other").also { it.clear() }

    @Test
    fun keysArePrefixedWithTheStoreName() = runTest {
        newStore().setString("theme", "dark")
        assertEquals("s:dark", rawItem("test.theme"))
        assertNull(rawItem("theme"))
    }

    @Test
    fun aValueThatIsntTaggedIsAbsent() = runTest {
        val store = newStore()
        setRawItem("test.theme", "dark")                             // written by something else
        assertNull(store.getString("theme"))
    }
}

private fun rawItem(key: String): String? = js("localStorage.getItem(key)")

private fun setRawItem(key: String, value: String): Unit = js("localStorage.setItem(key, value)")

actual val canCreateWithoutContext: Boolean = true

actual fun newPlatformContext(): PlatformContext = PlatformContext()
