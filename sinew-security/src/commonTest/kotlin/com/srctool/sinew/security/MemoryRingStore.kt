package com.srctool.sinew.security

import kotlinx.coroutines.yield

/**
 * A [RingStore] in memory, for targets whose real store a test can't reach. Each call suspends once, as real storage
 * does, so concurrent callers interleave.
 */
internal class MemoryRingStore<K> : RingStore<K> {
    var ring: Keyring<K>? = null
        private set
    var saves: Int = 0
        private set
    var loads: Int = 0
        private set

    override suspend fun load(): Keyring<K>? {
        yield()
        loads++
        return ring
    }

    override suspend fun save(ring: Keyring<K>) {
        yield()
        this.ring = ring
        saves++
    }
}
