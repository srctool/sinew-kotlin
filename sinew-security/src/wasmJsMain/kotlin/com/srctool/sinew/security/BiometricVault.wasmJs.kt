package com.srctool.sinew.security

import com.srctool.sinew.models.PlatformContext

internal actual fun platformBiometricVault(platform: PlatformContext, keyAlias: String, clearOnReinstall: Boolean): BiometricVault =
    UnavailableBiometricVault
