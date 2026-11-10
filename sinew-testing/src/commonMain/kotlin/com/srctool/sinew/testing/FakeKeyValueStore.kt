package com.srctool.sinew.testing

import com.srctool.sinew.storage.KeyValueStore

/**
 * A [KeyValueStore] in memory, behaving like the real one: a value read as another type than it was written is
 * absent, and `clear` empties only this store. A test can fail the next read or write with a platform storage error,
 * or the next write with a full disk; through `processStorageCall` they become `StorageReadException`,
 * `StorageWriteException` and `StorageFullException`.
  *
  * @property name The store's name.
 */
public class FakeKeyValueStore(override val name: String = "fake_prefs") : KeyValueStore {
    private val values = mutableMapOf<String, Any>()
    private var nextReadError: Throwable? = null
    private var nextWriteError: Throwable? = null

    /** Every stored value by key, for assertions. */
    public val snapshot: Map<String, Any> get() = values.toMap()

    /** Makes the next read throw [error], once. */
    public fun failNextRead(error: Throwable = platformStorageError("Read failed")) {
        nextReadError = error
    }

    /** Makes the next write (a set, `remove` or `clear`) throw [error], once, writing nothing. */
    public fun failNextWrite(error: Throwable = platformStorageError("Write failed")) {
        nextWriteError = error
    }

    /** Makes the next write fail as the device being full. */
    public fun failNextWriteWithDiskFull() {
        failNextWrite(diskFullError())
    }

    private fun read(): Map<String, Any> {
        nextReadError?.let { nextReadError = null; throw it }
        return values
    }

    private fun write(change: MutableMap<String, Any>.() -> Unit) {
        nextWriteError?.let { nextWriteError = null; throw it }
        values.change()
    }

    private inline fun <reified T> get(key: String): T? = read()[key] as? T

    /** As [KeyValueStore.getString]: the string under [key], or `null` when absent or written as another type. */
    override suspend fun getString(key: String): String? = get(key)
    /** As [KeyValueStore.setString], in memory. */
    override suspend fun setString(key: String, value: String): Unit = write { put(key, value) }
    /** As [KeyValueStore.getInt]: the integer under [key], or `null` when absent or written as another type. */
    override suspend fun getInt(key: String): Int? = get(key)
    /** As [KeyValueStore.setInt], in memory. */
    override suspend fun setInt(key: String, value: Int): Unit = write { put(key, value) }
    /** As [KeyValueStore.getBool]: the boolean under [key], or `null` when absent or written as another type. */
    override suspend fun getBool(key: String): Boolean? = get(key)
    /** As [KeyValueStore.setBool], in memory. */
    override suspend fun setBool(key: String, value: Boolean): Unit = write { put(key, value) }
    /** As [KeyValueStore.getDouble]: the double under [key], or `null` when absent or written as another type. */
    override suspend fun getDouble(key: String): Double? = get(key)
    /** As [KeyValueStore.setDouble], in memory. */
    override suspend fun setDouble(key: String, value: Double): Unit = write { put(key, value) }
    /** As [KeyValueStore.remove], in memory. */
    override suspend fun remove(key: String): Unit = write { remove(key) }
    /** As [KeyValueStore.clear]: empties this store only. */
    override suspend fun clear(): Unit = write { clear() }

    /** The stored keys, sorted. */
    override suspend fun keys(): List<String> = read().keys.sorted()
    /** The value under [key] as text, or `null` when absent. */
    override suspend fun read(key: String): String? = read()[key]?.toString()
}
