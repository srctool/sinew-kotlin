package com.srctool.sinew.testing

import com.srctool.sinew.network.RefreshTokenStore

/**
 * A [RefreshTokenStore] in memory, for `SessionTokenSource` tests. A test can fail the next read or write with a
 * platform storage error, as a secure store can.
 */
public class FakeRefreshTokenStore(token: String? = null) : RefreshTokenStore {
    /** The stored refresh token. */
    public var token: String? = token
        private set

    private var nextReadError: Throwable? = null
    private var nextWriteError: Throwable? = null

    /** Makes the next read throw [error], once. */
    public fun failNextRead(error: Throwable = platformStorageError("Read failed")) {
        nextReadError = error
    }

    /** Makes the next write or delete throw [error], once, changing nothing. */
    public fun failNextWrite(error: Throwable = platformStorageError("Write failed")) {
        nextWriteError = error
    }

    override suspend fun read(): String? {
        nextReadError?.let { nextReadError = null; throw it }
        return token
    }

    override suspend fun write(token: String) {
        nextWriteError?.let { nextWriteError = null; throw it }
        this.token = token
    }

    override suspend fun delete() {
        nextWriteError?.let { nextWriteError = null; throw it }
        token = null
    }
}
