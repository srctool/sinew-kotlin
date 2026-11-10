package com.srctool.sinew.docs

import com.srctool.sinew.models.PlatformContext
import com.srctool.sinew.security.BiometricAvailability
import com.srctool.sinew.security.BiometricVault
import com.srctool.sinew.security.FieldCipher
import com.srctool.sinew.storage.KeyValueStore
import com.srctool.sinew.storage.SecureStore

// region stores
suspend fun remember(platform: PlatformContext, refreshToken: String) {
    val prefs = KeyValueStore.create(platform, name = "orders")
    prefs.setString("theme", "dark")
    val theme = prefs.getString("theme") // null when missing or written as another type

    val secrets = SecureStore.create(platform)
    secrets.write("refreshToken", refreshToken)
    println("theme: $theme, token stored: ${secrets.read("refreshToken") != null}")
}
// endregion

// region cipher
suspend fun sealAndOpen(platform: PlatformContext): String {
    val cipher = FieldCipher.create(platform)
    val sealed = cipher.encrypt("Alice", context = "customer:42")
    return cipher.decrypt(sealed, context = "customer:42")
}
// endregion

// region vault
suspend fun offerBiometrics(platform: PlatformContext, refreshToken: String) {
    val vault = BiometricVault.create(platform)
    if (vault.availability() == BiometricAvailability.Available) {
        vault.store(refreshToken, reason = "Sign in with biometrics next time")
    }
    println(vault.unlock(reason = "Sign in"))
}
// endregion
