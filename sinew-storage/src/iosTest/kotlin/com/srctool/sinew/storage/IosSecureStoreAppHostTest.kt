package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The real store, with its key in the simulator's Keychain, run as an app by tool/ios-app-test.sh. */
class IosSecureStoreAppHostTest {
    @Test
    fun aSecretRoundTripsThroughTheKeychainKey() = runBlocking {
        val store = SecureStore.create(PlatformContext(), "apphost")
        store.write("token", "refresh-token")
        assertEquals("refresh-token", SecureStore.create(PlatformContext(), "apphost").read("token"))
        store.clearUserData()
        assertNull(store.read("token"))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun secretsLiveEncryptedInTheAppsOwnFile() = runBlocking {
        // What makes a reinstall start empty: an uninstall deletes the app's files, while Keychain items survive. The
        // secrets are in Application Support, encrypted; only the cipher's key is in the Keychain.
        SecureStore.create(PlatformContext(), "reinstall").write("token", "refresh-token")
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val file = NSFileManager.defaultManager.contentsAtPath("$support/sinew/reinstall_secure.preferences_pb")
        val bytes = assertNotNull(file, "the value file is in Application Support").toByteArray()
        assertFalse("refresh-token" in bytes.decodeToString(), "the file holds no plain secret")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).also { bytes ->
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
}
