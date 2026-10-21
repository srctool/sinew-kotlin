package com.srctool.sinew.testing

import com.srctool.sinew.storage.SecureStore

/**
 * A [SecureStore] in memory, without encryption. A test can fail the next read or write with a platform storage
 * error, or [loseKey] as a restore onto a new device does: from then on every read is absent, as the real store reads
 * a value it can't decrypt, and the lost values are deleted.
 */
public class FakeSecureStore(override val name: String = "fake") : SecureStore {
    private val values = mutableMapOf<String, String>()
    private var nextReadError: Throwable? = null
    private var nextWriteError: Throwable? = null
    private var keyLost = false

    /** Every stored secret by key, for assertions. */
    public val snapshot: Map<String, String> get() = values.toMap()

    /** Makes the next read throw [error], once. */
    public fun failNextRead(error: Throwable = platformStorageError("Read failed")) {
        nextReadError = error
    }

    /** Makes the next write (`write`, `delete` or `clearUserData`) throw [error], once, writing nothing. */
    public fun failNextWrite(error: Throwable = platformStorageError("Write failed")) {
        nextWriteError = error
    }

    /** Loses the key: every secret stored so far reads as absent and is deleted. Secrets written later work. */
    public fun loseKey() {
        keyLost = true
    }

    private fun checkRead() {
        nextReadError?.let { nextReadError = null; throw it }
        if (keyLost) {
            values.clear()
            keyLost = false
        }
    }

    private fun write(change: MutableMap<String, String>.() -> Unit) {
        nextWriteError?.let { nextWriteError = null; throw it }
        values.change()
    }

    override suspend fun read(key: String): String? {
        checkRead()
        return values[key]
    }

    override suspend fun write(key: String, value: String): Unit = write { put(key, value) }
    override suspend fun delete(key: String): Unit = write { remove(key) }
    override suspend fun clearUserData(): Unit = write { clear() }

    override suspend fun keys(): List<String> {
        checkRead()
        return values.keys.sorted()
    }
}
