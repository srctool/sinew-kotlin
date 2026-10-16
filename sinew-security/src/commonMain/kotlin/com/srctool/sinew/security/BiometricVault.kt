package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext

/**
 * One small secret, typically the refresh token, that can be read only after a real biometric match. The secret is
 * protected **by** the biometric key or access control, never by a prompt in front of a normal read, and enrolling a
 * new face or fingerprint makes it unreadable.
 *
 * | Target | Protected by |
 * |---|---|
 * | Android | an Android Keystore AES-256-GCM key usable only after a strong biometric match, through `BiometricPrompt` |
 * | iOS | a Keychain item with `biometryCurrentSet` access control, this device only, needing a passcode |
 * | JVM, wasm | nothing: [availability] is [BiometricAvailability.Unavailable] and every call fails with it |
 *
 * Offer it only after a real sign-in, keep a password fallback, and store a refresh token, never a password.
 */
public interface BiometricVault {
    /** Whether biometrics can be offered now. */
    public suspend fun availability(): BiometricAvailability

    /** Whether a secret is stored, without a prompt: decides whether to offer biometric sign-in. */
    public suspend fun isStored(): Boolean

    /**
     * Stores [secret], replacing any stored one. On Android this shows the biometric prompt with [reason] as its
     * title, because the key needs a match to encrypt too; iOS stores without a prompt.
     */
    public suspend fun store(secret: String, reason: String): BiometricStore

    /** Shows the system prompt with [reason] and returns the secret after a match. */
    public suspend fun unlock(reason: String): BiometricUnlock

    /** Deletes the secret and its key. */
    public suspend fun disable()

    public companion object {
        /**
         * The platform's vault for [keyAlias]. With [clearOnReinstall] (iOS), the first use after an install deletes
         * an item the previous install left in the Keychain, so a reinstalled app never unlocks an old session.
         * On Android, prompts need the app's resumed activity to be a `FragmentActivity`, and the vault learns of
         * activities from its creation on: create it at app start (the `Application` or the DI root).
         */
        public fun create(
            platform: PlatformContext,
            keyAlias: String = "sinew.biometric",
            clearOnReinstall: Boolean = true,
        ): BiometricVault {
            require(keyAlias.isNotBlank()) { "keyAlias must not be blank" }
            return platformBiometricVault(platform, keyAlias, clearOnReinstall)
        }
    }
}

/** Whether to offer biometrics. */
public enum class BiometricAvailability {
    /** Biometric hardware with an enrolled face or finger. */
    Available,

    /** The hardware is there, but nothing is enrolled: the app may suggest enrolling in Settings. */
    NotEnrolled,

    /** No usable biometrics (no hardware, no passcode, a desktop or a browser): never offer it. */
    Unavailable,
}

/** Why [BiometricVault.store] or [BiometricVault.unlock] didn't complete. A user outcome, not an error. */
public enum class BiometricFailure {
    /** The user, the app or the system dismissed the prompt: offer the password. */
    Cancelled,

    /** Too many failed attempts: offer the password. */
    LockedOut,

    /** Nothing is stored, or the enrollment changed and the secret was deleted: sign in with the password, offer opt-in again. */
    Invalidated,

    /** Biometrics can't be used here now. */
    Unavailable,
}

/** The outcome of [BiometricVault.store]. */
public sealed interface BiometricStore {
    /** The secret is stored. */
    public data object Stored : BiometricStore

    /** Nothing was stored. */
    public data class Failed(val reason: BiometricFailure) : BiometricStore
}

/** The outcome of [BiometricVault.unlock]. */
public sealed interface BiometricUnlock {
    /** A real biometric match released [secret]. */
    public class Unlocked(public val secret: String) : BiometricUnlock {
        override fun equals(other: Any?): Boolean = other is Unlocked && other.secret == secret
        override fun hashCode(): Int = secret.hashCode()

        /** Never the secret: an outcome may be logged. */
        override fun toString(): String = "Unlocked(secret=***)"
    }

    /** The secret wasn't released. */
    public data class Failed(val reason: BiometricFailure) : BiometricUnlock
}

internal expect fun platformBiometricVault(platform: PlatformContext, keyAlias: String, clearOnReinstall: Boolean): BiometricVault

/** The vault where there are no biometrics (JVM, wasm). */
internal object UnavailableBiometricVault : BiometricVault {
    override suspend fun availability(): BiometricAvailability = BiometricAvailability.Unavailable
    override suspend fun isStored(): Boolean = false
    override suspend fun store(secret: String, reason: String): BiometricStore = BiometricStore.Failed(BiometricFailure.Unavailable)
    override suspend fun unlock(reason: String): BiometricUnlock = BiometricUnlock.Failed(BiometricFailure.Unavailable)
    override suspend fun disable() {}
}
