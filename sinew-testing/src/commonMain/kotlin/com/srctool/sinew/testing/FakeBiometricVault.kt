package com.srctool.sinew.testing

import com.srctool.sinew.security.BiometricAvailability
import com.srctool.sinew.security.BiometricFailure
import com.srctool.sinew.security.BiometricStore
import com.srctool.sinew.security.BiometricUnlock
import com.srctool.sinew.security.BiometricVault

/**
 * A [BiometricVault] with no prompt: [availability] is what the test sets, a store succeeds unless the test scripts
 * a failure, and an unlock releases the stored secret unless the test scripts one. [prompts] records each prompt's
 * reason. Like the real vault, nothing stored unlocks as [BiometricFailure.Invalidated], and anything but
 * [BiometricAvailability.Available] fails as [BiometricFailure.Unavailable].
 */
public class FakeBiometricVault(
    /** What [availability] reports. */
    public var availability: BiometricAvailability = BiometricAvailability.Available,
) : BiometricVault {
    private var secret: String? = null
    private val nextStores = ArrayDeque<BiometricFailure>()
    private val nextUnlocks = ArrayDeque<BiometricFailure>()
    private val _prompts = mutableListOf<String>()

    /** The reason of every prompt shown (each `store` and `unlock`), in order. */
    public val prompts: List<String> get() = _prompts.toList()

    /** The stored secret, for assertions. */
    public val storedSecret: String? get() = secret

    /** Makes the next `store` fail with [reason], storing nothing. Calls queue up. */
    public fun nextStore(reason: BiometricFailure) {
        nextStores += reason
    }

    /** Makes the next `unlock` fail with [reason]. Calls queue up. */
    public fun nextUnlock(reason: BiometricFailure) {
        nextUnlocks += reason
    }

    /** Invalidates the secret, as enrolling a new face or finger does. */
    public fun invalidate() {
        secret = null
    }

    override suspend fun availability(): BiometricAvailability = availability

    override suspend fun isStored(): Boolean = secret != null

    override suspend fun store(secret: String, reason: String): BiometricStore {
        if (availability != BiometricAvailability.Available) return BiometricStore.Failed(BiometricFailure.Unavailable)
        _prompts += reason
        nextStores.removeFirstOrNull()?.let {
            this.secret = null
            return BiometricStore.Failed(it)
        }
        this.secret = secret
        return BiometricStore.Stored
    }

    override suspend fun unlock(reason: String): BiometricUnlock {
        val stored = secret ?: return BiometricUnlock.Failed(BiometricFailure.Invalidated)
        if (availability != BiometricAvailability.Available) return BiometricUnlock.Failed(BiometricFailure.Unavailable)
        _prompts += reason
        nextUnlocks.removeFirstOrNull()?.let { return BiometricUnlock.Failed(it) }
        return BiometricUnlock.Unlocked(stored)
    }

    override suspend fun disable() {
        secret = null
    }
}
