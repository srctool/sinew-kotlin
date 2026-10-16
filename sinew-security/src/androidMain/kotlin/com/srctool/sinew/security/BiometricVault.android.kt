package com.srctool.sinew.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The shared-preferences file holding each alias's IV and ciphertext. Exclude it from backup. */
internal const val VAULT_PREFS: String = "sinew.security.biometric"

internal actual fun platformBiometricVault(platform: PlatformContext, keyAlias: String, clearOnReinstall: Boolean): BiometricVault =
    AndroidBiometricVault(platform.context.applicationContext as Application, keyAlias)

/**
 * The secret is AES-256-GCM encrypted by an Android Keystore key that needs a fresh strong biometric match for every
 * use, encrypting included, and that enrolling a new fingerprint invalidates. The key is unlocked by `BiometricPrompt`
 * with a `CryptoObject`, so no match means no key. A store makes a new key, so opting in again resets an invalidation.
 */
internal class AndroidBiometricVault(private val app: Application, private val alias: String) : BiometricVault {
    private val prefs = app.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)
    private val activities = ResumedActivity.of(app)
    private val lock = Mutex()

    override suspend fun availability(): BiometricAvailability = availabilityOf(BiometricManager.from(app).canAuthenticate(BIOMETRIC_STRONG))

    override suspend fun isStored(): Boolean = withContext(Dispatchers.IO) { prefs.contains("$alias.value") && keyStore().containsAlias(alias) }

    override suspend fun store(secret: String, reason: String): BiometricStore = lock.withLock {
        if (availability() != BiometricAvailability.Available) return@withLock BiometricStore.Failed(BiometricFailure.Unavailable)
        val cipher = withContext(Dispatchers.IO) {
            deleteAll()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey()) }   // the Keystore picks the IV
        }
        when (val outcome = authenticate(reason, cipher)) {
            is Authenticated -> {
                val sealed = outcome.cipher.doFinal(secret.encodeToByteArray())
                prefs.edit()
                    .putString("$alias.iv", Base64.encodeToString(outcome.cipher.iv, Base64.NO_WRAP))
                    .putString("$alias.value", Base64.encodeToString(sealed, Base64.NO_WRAP))
                    .commit()
                BiometricStore.Stored
            }
            is NotAuthenticated -> {
                withContext(Dispatchers.IO) { deleteAll() }
                BiometricStore.Failed(outcome.reason)
            }
        }
    }

    override suspend fun unlock(reason: String): BiometricUnlock = lock.withLock {
        val iv = prefs.getString("$alias.iv", null)
        val value = prefs.getString("$alias.value", null)
        val key = withContext(Dispatchers.IO) { keyStore().getKey(alias, null) as SecretKey? }
        if (iv == null || value == null || key == null) return@withLock BiometricUnlock.Failed(BiometricFailure.Invalidated)
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            withContext(Dispatchers.IO) { deleteAll() }                   // a new fingerprint was enrolled
            return@withLock BiometricUnlock.Failed(BiometricFailure.Invalidated)
        }
        when (val outcome = authenticate(reason, cipher)) {
            is Authenticated -> try {
                BiometricUnlock.Unlocked(outcome.cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).decodeToString())
            } catch (_: GeneralSecurityException) {
                withContext(Dispatchers.IO) { deleteAll() }               // the stored value was changed
                BiometricUnlock.Failed(BiometricFailure.Invalidated)
            }
            is NotAuthenticated -> BiometricUnlock.Failed(outcome.reason)
        }
    }

    override suspend fun disable() {
        lock.withLock { withContext(Dispatchers.IO) { deleteAll() } }
    }

    private fun deleteAll() {
        prefs.edit().remove("$alias.iv").remove("$alias.value").commit()
        keyStore().deleteEntry(alias)
    }

    private fun newKey(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)   // every use needs a fresh match
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    /**
     * Shows the prompt on the resumed `FragmentActivity` and returns the unlocked cipher, or why not.
     *
     * `BiometricPrompt` silently drops a request made while a previous prompt is still closing, and the caller would
     * wait forever. So this waits for any prompt to be gone before showing one, and for its own to be gone before
     * returning, still holding the vault's lock. A cancelled caller dismisses the prompt the same way.
     */
    private suspend fun authenticate(reason: String, cipher: Cipher): Authentication {
        val activity = activities.current()
        if (activity == null) {
            Log.w(TAG, "BiometricVault needs the resumed activity to be a FragmentActivity; reporting Unavailable")
            return NotAuthenticated(BiometricFailure.Unavailable)
        }
        awaitPromptGone(activity)
        val outcome = CompletableDeferred<Authentication>()
        val prompt = withContext(Dispatchers.Main.immediate) {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlocked = result.cryptoObject?.cipher
                        outcome.complete(if (unlocked != null) Authenticated(unlocked) else NotAuthenticated(BiometricFailure.Unavailable))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        outcome.complete(NotAuthenticated(failureOf(errorCode)))
                    }
                    // onAuthenticationFailed: a finger that didn't match; the prompt stays up for another try.
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(reason)
                .setNegativeButtonText(activity.getString(android.R.string.cancel))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            prompt
        }
        return try {
            outcome.await().also { withContext(NonCancellable) { awaitPromptGone(activity) } }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                prompt.cancelAuthentication()
                withTimeoutOrNull(2_000) { outcome.await() }
                awaitPromptGone(activity)
            }
            throw e
        }
    }

    /** Waits, up to 2 s, until no AndroidX biometric prompt is attached to [activity]. */
    private suspend fun awaitPromptGone(activity: FragmentActivity) {
        withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(2_000) {
                while (activity.supportFragmentManager.findFragmentByTag(PROMPT_FRAGMENT) != null) delay(50)
            }
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG = "SinewBiometricVault"
        const val PROMPT_FRAGMENT = "androidx.biometric.BiometricFragment"     // AndroidX BiometricPrompt's own tag

        fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
}

private sealed interface Authentication
private class Authenticated(val cipher: Cipher) : Authentication
private class NotAuthenticated(val reason: BiometricFailure) : Authentication

internal fun availabilityOf(canAuthenticate: Int): BiometricAvailability = when (canAuthenticate) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
    else -> BiometricAvailability.Unavailable
}

internal fun failureOf(errorCode: Int): BiometricFailure = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_TIMEOUT -> BiometricFailure.Cancelled
    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricFailure.LockedOut
    else -> BiometricFailure.Unavailable                                  // no hardware, nothing enrolled, an update required
}

/** The app's resumed activity, if it's a `FragmentActivity`: `BiometricPrompt` needs one. One tracker per app. */
internal class ResumedActivity private constructor() : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed: WeakReference<FragmentActivity>? = null

    fun current(): FragmentActivity? = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun onActivityResumed(activity: Activity) {
        resumed = (activity as? FragmentActivity)?.let(::WeakReference)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        @Volatile
        private var tracker: ResumedActivity? = null

        fun of(app: Application): ResumedActivity = tracker ?: synchronized(this) {
            tracker ?: ResumedActivity().also {
                app.registerActivityLifecycleCallbacks(it)
                tracker = it
            }
        }
    }
}
