package com.srctool.sinew.storage

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.test.runTest
import java.io.File
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The real store on a device: DataStore, with the key in a Tink keyset under an Android Keystore master key. */
class AndroidSecureStoreDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val platform = PlatformContext(context)
    private val namespace = "devicetest"
    private val alias = "sinew.secure.$namespace"

    @BeforeTest
    @AfterTest
    fun reset() = runTest {
        SecureStore.create(platform, namespace).clearUserData()
        deleteKey()
    }

    private fun deleteKey() {
        context.getSharedPreferences("sinew.security.keysets", Context.MODE_PRIVATE).edit().remove(alias).commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aSecretRoundTripsEncryptedOnDisk() = runTest {
        SecureStore.create(platform, namespace).write("token", "refresh-token")
        assertEquals("refresh-token", SecureStore.create(platform, namespace).read("token"))
        val file = File(context.filesDir, "datastore/${namespace}_secure.preferences_pb")
        assertFalse("refresh-token" in file.readBytes().decodeToString(), "the file holds no plain secret")
    }

    @Test
    fun aLostKeystoreKeyMakesTheSecretAbsentAndDeletesIt() = runTest {
        SecureStore.create(platform, namespace).write("token", "refresh-token")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)   // as a restore onto a new device
        assertNull(SecureStore.create(platform, namespace).read("token"))
        assertNull(KeyValueStore.create(platform, "${namespace}_secure").getString("token"))
    }
}
