package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData

internal actual fun platformBiometricVault(platform: PlatformContext, keyAlias: String, clearOnReinstall: Boolean): BiometricVault =
    IosBiometricVault(keyAlias, clearOnReinstall)

/**
 * The secret is a Keychain generic-password item (`service = keyAlias`, `account = "secret"`) with
 * `biometryCurrentSet` access control: readable only after a match by a face or finger enrolled when it was stored,
 * on this device, while a passcode is set. Reading it shows the system prompt; adding it doesn't.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosBiometricVault(private val service: String, private val clearOnReinstall: Boolean) : BiometricVault {
    private val lock = Mutex()
    private var installChecked = false

    /** On the first use after an install, deletes the item a previous install left behind. */
    private fun clearAfterReinstall() {
        if (installChecked) return
        if (clearOnReinstall && InstallMarker("biometric.$service").claim()) delete()
        installChecked = true
    }

    override suspend fun availability(): BiometricAvailability = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        when {
            LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error.ptr) -> BiometricAvailability.Available
            error.value?.code == LAErrorBiometryNotEnrolled -> BiometricAvailability.NotEnrolled
            else -> BiometricAvailability.Unavailable
        }
    }

    override suspend fun isStored(): Boolean = lock.withLock {
        clearAfterReinstall()
        val context = LAContext().apply { interactionNotAllowed = true }  // answers without a prompt
        val status = withQuery({ it.putBridged(kSecUseAuthenticationContext, context) }) { query -> SecItemCopyMatching(query, null) }
        status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    override suspend fun store(secret: String, reason: String): BiometricStore = lock.withLock {
        clearAfterReinstall()
        if (availability() != BiometricAvailability.Available) return@withLock BiometricStore.Failed(BiometricFailure.Unavailable)
        val access = SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, kSecAccessControlBiometryCurrentSet, null)
            ?: return@withLock BiometricStore.Failed(BiometricFailure.Unavailable)
        try {
            delete()
            val status = withQuery({
                it.put(kSecAttrAccessControl, access)
                it.putBridged(kSecValueData, secret.encodeToByteArray().toNSData())
            }) { query -> SecItemAdd(query, null) }
            if (status == errSecSuccess) BiometricStore.Stored else BiometricStore.Failed(BiometricFailure.Unavailable)
        } finally {
            CFRelease(access)
        }
    }

    override suspend fun unlock(reason: String): BiometricUnlock = lock.withLock {
        clearAfterReinstall()
        val context = LAContext().apply { localizedReason = reason }
        // The read blocks until the prompt is answered. If the caller is cancelled meanwhile, invalidating the
        // context dismisses the prompt, and the read returns.
        val read = CoroutineScope(Dispatchers.IO).async {
            withQuery({
                it.putBridged(kSecUseAuthenticationContext, context)
                it.put(kSecReturnData, kCFBooleanTrue)
            }) { query ->
                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    val status = SecItemCopyMatching(query, result.ptr)
                    status to (if (status == errSecSuccess) CFBridgingRelease(result.value) as NSData else null)
                }
            }
        }
        val (status, data) = try {
            read.await()
        } catch (e: CancellationException) {
            context.invalidate()
            throw e
        }
        when {
            data != null -> BiometricUnlock.Unlocked(data.toByteArray().decodeToString())
            status == errSecUserCanceled -> BiometricUnlock.Failed(BiometricFailure.Cancelled)
            status == errSecItemNotFound -> BiometricUnlock.Failed(BiometricFailure.Invalidated)
            lockedOut() -> BiometricUnlock.Failed(BiometricFailure.LockedOut)
            else -> BiometricUnlock.Failed(failureOfStatus(status))
        }
    }

    override suspend fun disable() {
        lock.withLock {
            clearAfterReinstall()
            delete()
        }
    }

    private fun delete() {
        withQuery { SecItemDelete(it) }
    }

    private fun lockedOut(): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        !LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error.ptr) &&
            error.value?.code == LAErrorBiometryLockout
    }

    private fun <T> withQuery(extra: (CFQuery) -> Unit = {}, block: (CFDictionaryRef?) -> T): T = withDictionary({ query ->
        query.put(kSecClass, kSecClassGenericPassword)
        query.putBridged(kSecAttrService, service)
        query.putBridged(kSecAttrAccount, "secret")
        extra(query)
    }, block)
}

/** What an `OSStatus` other than success, a cancel or a missing item means for the caller. */
internal fun failureOfStatus(status: Int): BiometricFailure = when (status) {
    errSecInteractionNotAllowed -> BiometricFailure.Unavailable
    else -> BiometricFailure.Cancelled                                   // errSecAuthFailed: no match, prompt dismissed
}

/**
 * A marker file in Application Support, which an uninstall deletes while the Keychain keeps its items. Each owner
 * (the vault, each secure store) has its own marker, so one owner claiming it never hides a reinstall from another.
 */
@OptIn(ExperimentalForeignApi::class)
internal class InstallMarker(private val name: String) {
    private val path: String by lazy {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        "$support/sinew/installed/$name"
    }

    /** `true` the first time it's called since the app was installed: the marker didn't exist, and now does. */
    fun claim(): Boolean {
        val files = NSFileManager.defaultManager
        if (files.fileExistsAtPath(path)) return false
        files.createDirectoryAtPath(path.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null)
        files.createFileAtPath(path, contents = null, attributes = null)
        return true
    }

    /** Deletes the marker, as an uninstall does: for tests. */
    fun remove() {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
