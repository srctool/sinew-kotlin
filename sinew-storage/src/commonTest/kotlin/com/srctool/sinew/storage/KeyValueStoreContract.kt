package com.srctool.sinew.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The suite every target's [KeyValueStore] passes, over its real storage, empty for each test. */
abstract class KeyValueStoreContract {
    /** An empty store. */
    abstract suspend fun newStore(): KeyValueStore

    /** Another store with the same name as the last [newStore]. */
    abstract suspend fun reopen(): KeyValueStore

    /** An empty store with another name. */
    abstract suspend fun otherStore(): KeyValueStore

    @Test
    fun eachTypeRoundTrips() = runTest {
        val store = newStore()
        for (text in listOf("", "dark", "Ä 😀 日本語", "x".repeat(5_000))) {
            store.setString("text", text)
            assertEquals(text, store.getString("text"))
        }
        for (number in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            store.setInt("number", number)
            assertEquals(number, store.getInt("number"))
        }
        for (flag in listOf(true, false)) {
            store.setBool("flag", flag)
            assertEquals(flag, store.getBool("flag"))
        }
        for (decimal in listOf(0.0, -2.5, 1e-300, Double.MAX_VALUE)) {
            store.setDouble("decimal", decimal)
            assertEquals(decimal, store.getDouble("decimal"))
        }
    }

    @Test
    fun aMissingKeyIsAbsent() = runTest {
        val store = newStore()
        assertNull(store.getString("missing"))
        assertNull(store.getInt("missing"))
        assertNull(store.getBool("missing"))
        assertNull(store.getDouble("missing"))
    }

    @Test
    fun aValueReadAsAnotherTypeIsAbsent() = runTest {
        val store = newStore()
        store.setInt("key", 7)
        assertNull(store.getString("key"))
        assertNull(store.getBool("key"))
        assertNull(store.getDouble("key"))
        assertEquals(7, store.getInt("key"))
    }

    @Test
    fun writingAnotherTypeReplacesTheValue() = runTest {
        val store = newStore()
        store.setString("key", "seven")
        store.setInt("key", 7)
        assertNull(store.getString("key"))
        assertEquals(7, store.getInt("key"))
    }

    @Test
    fun removeDeletesOneKeyWhateverItsType() = runTest {
        val store = newStore()
        store.setBool("flag", true)
        store.setString("theme", "dark")
        store.remove("flag")
        store.remove("never-written")
        assertNull(store.getBool("flag"))
        assertEquals("dark", store.getString("theme"))
    }

    @Test
    fun clearEmptiesThisStoreOnly() = runTest {
        val store = newStore()
        val other = otherStore()
        store.setString("theme", "dark")
        store.setInt("launches", 3)
        other.setString("theme", "light")
        store.clear()
        assertNull(store.getString("theme"))
        assertNull(store.getInt("launches"))
        assertEquals("light", other.getString("theme"))
    }

    @Test
    fun storesWithTheSameNameShareTheirValues() = runTest {
        newStore().setString("theme", "dark")
        assertEquals("dark", reopen().getString("theme"))
    }

    @Test
    fun itCanBeInspected() = runTest {
        val store = newStore()
        assertEquals(emptyList(), store.keys())
        store.setString("theme", "dark")
        store.setInt("launches", 3)
        store.setBool("onboarded", true)
        store.setDouble("ratio", 1.5)
        assertEquals(listOf("launches", "onboarded", "ratio", "theme"), store.keys())
        assertEquals("dark", store.read("theme"))
        assertEquals("3", store.read("launches"))
        assertEquals("true", store.read("onboarded"))
        assertEquals("1.5", store.read("ratio"))
        assertNull(store.read("missing"))
        assertEquals(emptyList(), otherStore().keys())
    }

    @Test
    fun concurrentWritesAllLand() = runTest {
        val store = newStore()
        List(50) { i -> async { store.setInt("key$i", i) } }.awaitAll()
        for (i in 0 until 50) assertEquals(i, store.getInt("key$i"))
    }
}
