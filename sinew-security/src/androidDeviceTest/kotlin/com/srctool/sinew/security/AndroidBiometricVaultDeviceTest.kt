package com.srctool.sinew.security

import android.app.Application
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The prompt's host: `BiometricPrompt` needs a `FragmentActivity`. It keeps the screen on: a screen timeout cancels a prompt. */
class VaultHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * The vault on an emulator with a PIN and finger 1 enrolled, driven by tool/android-biometric-test.sh: a test logs
 * `TOUCH <finger>`, `BACK`, `SKIP` or `ENROLL` (tag `SinewBiometricTest`) just before the prompt, and the script acts. Skipped
 * unless the script passes `sinewBiometrics=true`, since a prompt would otherwise wait forever.
 */
class AndroidBiometricVaultDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val alias = "sinew.test.biometric"
    private val vault = BiometricVault.create(PlatformContext(app), alias)   // before the activity resumes
    private lateinit var host: ActivityScenario<VaultHostActivity>

    private fun ask(action: String) {
        Log.i("SinewBiometricTest", action)
    }

    @BeforeTest
    fun start() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sinewBiometrics") == "true")
        runBlocking { vault.disable() }
        host = ActivityScenario.launch(VaultHostActivity::class.java)
    }

    @AfterTest
    fun stop() {
        if (::host.isInitialized) host.close()
        runBlocking { vault.disable() }
    }

    @Test
    fun anEnrolledFingerMakesItAvailable() = runBlocking {
        assertEquals(BiometricAvailability.Available, vault.availability())
    }

    @Test
    fun aStoredSecretUnlocksWithTheEnrolledFinger() = runBlocking {
        assertFalse(vault.isStored())
        ask("TOUCH 1")
        assertEquals(BiometricStore.Stored, vault.store("refresh-token", "Turn on fingerprint sign-in"))
        assertTrue(vault.isStored())
        ask("TOUCH 1")
        assertEquals(BiometricUnlock.Unlocked("refresh-token"), vault.unlock("Sign in"))
    }

    @Test
    fun anotherFingerReleasesNothingAndBackCancels() = runBlocking {
        ask("TOUCH 1")
        assertEquals(BiometricStore.Stored, vault.store("refresh-token", "Turn on fingerprint sign-in"))
        ask("TOUCH 2")                                                    // not enrolled: the prompt stays up
        ask("BACK")
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Cancelled), vault.unlock("Sign in"))
        assertTrue(vault.isStored())
    }

    @Test
    fun cancellingTheStoreLeavesNothingStored() = runBlocking {
        ask("BACK")
        assertEquals(BiometricStore.Failed(BiometricFailure.Cancelled), vault.store("refresh-token", "Turn on fingerprint sign-in"))
        assertFalse(vault.isStored())
    }

    @Test
    fun cancellingTheCallerDismissesThePrompt() = runBlocking {
        ask("TOUCH 1")
        assertEquals(BiometricStore.Stored, vault.store("refresh-token", "Turn on fingerprint sign-in"))
        var outcome: BiometricUnlock? = null
        val unlocking = launch { outcome = vault.unlock("Sign in") }
        delay(1_500)
        unlocking.cancel()
        unlocking.join()
        assertNull(outcome)
        ask("SKIP")                                                       // the cancelled prompt isn't to be answered
        ask("TOUCH 1")                                                    // a new prompt works
        assertEquals(BiometricUnlock.Unlocked("refresh-token"), vault.unlock("Sign in"))
    }

    @Test
    fun disableDeletesTheSecretAndItsKey() = runBlocking {
        ask("TOUCH 1")
        assertEquals(BiometricStore.Stored, vault.store("refresh-token", "Turn on fingerprint sign-in"))
        vault.disable()
        assertFalse(vault.isStored())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Invalidated), vault.unlock("Sign in"))
    }

    @Test
    fun enrollingAnotherFingerInvalidatesTheSecret() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sinewEnroll") == "true")
        ask("TOUCH 1")
        assertEquals(BiometricStore.Stored, vault.store("refresh-token", "Turn on fingerprint sign-in"))
        assertTrue(keyUsable(), "the key works before the enrollment")
        ask("ENROLL")
        var waited = 0
        while (keyUsable() && waited++ < 180) delay(1_000)                 // the script enrolls finger 2 in Settings
        assertFalse(keyUsable(), "the key is invalidated by the new enrollment")
        // No prompt needed: the invalidated key fails before one would show (the app may still be behind Settings).
        assertEquals(BiometricUnlock.Failed(BiometricFailure.Invalidated), vault.unlock("Sign in"))
        assertFalse(vault.isStored())
    }

    /** Whether the vault key can still start a decryption: a new enrollment makes it throw. */
    private fun keyUsable(): Boolean {
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey? ?: return false
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12)))
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            false
        }
    }
}
