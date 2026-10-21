package com.srctool.sinew.testing

import com.srctool.sinew.exception.StorageFullException
import com.srctool.sinew.exception.StorageReadException
import com.srctool.sinew.exception.StorageWriteException
import com.srctool.sinew.models.Result
import com.srctool.sinew.storage.StorageAccess
import com.srctool.sinew.storage.processStorageCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The store fakes, and their failures through the app's real storage guard on every target. */
class StoreFakesTest {
    private suspend fun guarded(access: StorageAccess, block: suspend () -> Unit) =
        assertIs<Result.Failure>(processStorageCall("ORD", "SAVE", access, block)).error

    @Test
    fun platformStorageErrorsAreClassifiedByTheRealGuard() = runTest {
        val read = guarded(StorageAccess.Read) { throw platformStorageError() }
        assertIs<StorageReadException>(read)
        assertEquals("ORD-R-SAVE-SR", read.code)
        assertIs<StorageWriteException>(guarded(StorageAccess.Write) { throw platformStorageError() })
        assertIs<StorageFullException>(guarded(StorageAccess.Write) { throw diskFullError() })
    }

    @Test
    fun keyValueStoreRoundTripsEachType() = runTest {
        val store = FakeKeyValueStore()
        store.setString("theme", "dark")
        store.setInt("launches", 3)
        store.setBool("onboarded", true)
        store.setDouble("ratio", 1.5)
        assertEquals("dark", store.getString("theme"))
        assertEquals(3, store.getInt("launches"))
        assertEquals(true, store.getBool("onboarded"))
        assertEquals(1.5, store.getDouble("ratio"))
        assertEquals(mapOf("theme" to "dark", "launches" to 3, "onboarded" to true, "ratio" to 1.5), store.snapshot)
    }

    @Test
    fun keyValueStoreReadsAnotherTypeAsAbsent() = runTest {
        val store = FakeKeyValueStore()
        store.setInt("key", 7)
        assertNull(store.getString("key"))
        assertNull(store.getBool("key"))
        assertNull(store.getDouble("key"))
        assertNull(store.getInt("missing"))
    }

    @Test
    fun keyValueStoreRemovesAndClears() = runTest {
        val store = FakeKeyValueStore()
        store.setString("a", "1")
        store.setString("b", "2")
        store.remove("a")
        assertEquals(mapOf("b" to "2"), store.snapshot)
        store.clear()
        assertEquals(emptyMap(), store.snapshot)
    }

    @Test
    fun keyValueStoreCanBeInspected() = runTest {
        val store = FakeKeyValueStore("orders")
        store.setString("theme", "dark")
        store.setInt("launches", 3)
        assertEquals("orders", store.name)
        assertEquals(listOf("launches", "theme"), store.keys())
        assertEquals("3", store.read("launches"))
        assertNull(store.read("missing"))
    }

    @Test
    fun keyValueStoreFailsTheNextReadOnce() = runTest {
        val store = FakeKeyValueStore()
        store.setString("theme", "dark")
        val error = IllegalStateException("custom")
        store.failNextRead(error)
        assertSame(error, assertFails { store.getString("theme") })
        assertEquals("dark", store.getString("theme"))
        store.failNextRead()
        assertIs<StorageReadException>(guarded(StorageAccess.Read) { store.getString("theme") })
    }

    @Test
    fun keyValueStoreFailsTheNextWriteOnceWritingNothing() = runTest {
        val store = FakeKeyValueStore()
        store.failNextWrite()
        assertIs<StorageWriteException>(guarded(StorageAccess.Write) { store.setString("theme", "dark") })
        assertEquals(emptyMap(), store.snapshot)
        store.setString("theme", "dark")
        assertEquals("dark", store.getString("theme"))
    }

    @Test
    fun keyValueStoreFailsTheNextWriteWithADiskFull() = runTest {
        val store = FakeKeyValueStore()
        store.failNextWriteWithDiskFull()
        assertIs<StorageFullException>(guarded(StorageAccess.Write) { store.setInt("launches", 1) })
        store.setInt("launches", 1)
        assertEquals(1, store.getInt("launches"))
    }

    @Test
    fun secureStoreRoundTripsDeletesAndClears() = runTest {
        val store = FakeSecureStore()
        store.write("a", "1")
        store.write("b", "2")
        assertEquals("1", store.read("a"))
        store.delete("a")
        assertNull(store.read("a"))
        assertEquals(listOf("b"), store.keys())
        store.clearUserData()
        assertEquals(emptyMap(), store.snapshot)
        assertEquals("fake", store.name)
    }

    @Test
    fun secureStoreFailsTheNextReadAndWriteOnce() = runTest {
        val store = FakeSecureStore()
        store.write("token", "refresh")
        store.failNextRead()
        assertIs<StorageReadException>(guarded(StorageAccess.Read) { store.read("token") })
        assertEquals("refresh", store.read("token"))
        store.failNextWrite()
        assertIs<StorageWriteException>(guarded(StorageAccess.Write) { store.write("token", "new") })
        assertEquals("refresh", store.read("token"))
        store.failNextWrite()
        assertFails { store.clearUserData() }
        assertEquals("refresh", store.read("token"))
    }

    @Test
    fun aLostKeyMakesEveryStoredSecretAbsentButNewOnesWork() = runTest {
        val store = FakeSecureStore()
        store.write("a", "1")
        store.write("b", "2")
        store.loseKey()
        assertNull(store.read("a"))
        assertNull(store.read("b"))
        assertEquals(emptyList(), store.keys())
        store.write("a", "new")
        assertEquals("new", store.read("a"))
    }

    @Test
    fun aLostKeyLeavesNoKeysToInspect() = runTest {
        val store = FakeSecureStore()
        store.write("a", "1")
        store.loseKey()
        assertEquals(emptyList(), store.keys())
    }

    @Test
    fun refreshTokenStoreHoldsReplacesAndDeletes() = runTest {
        val store = FakeRefreshTokenStore("first")
        assertEquals("first", store.read())
        store.write("second")
        assertEquals("second", store.token)
        store.delete()
        assertNull(store.read())
    }

    @Test
    fun refreshTokenStoreFailsTheNextReadAndWriteOnce() = runTest {
        val store = FakeRefreshTokenStore("token")
        store.failNextRead()
        assertIs<StorageReadException>(guarded(StorageAccess.Read) { store.read() })
        assertEquals("token", store.read())
        store.failNextWrite()
        assertFails { store.write("new") }
        assertEquals("token", store.read())
        store.failNextWrite()
        assertFails { store.delete() }
        assertEquals("token", store.read())
    }
}
