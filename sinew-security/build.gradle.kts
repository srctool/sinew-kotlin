plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "FieldCipher and BiometricVault on vetted crypto per platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(kotlinLibs.coroutines.core)
        }
        androidMain.dependencies {
            implementation(securityLibs.tink.android)
            implementation(androidxLibs.biometric)
        }
        jvmMain.dependencies { implementation(securityLibs.tink) }
        iosMain.dependencies {
            implementation(securityLibs.cryptography.core)
            implementation(securityLibs.cryptography.provider.cryptokit)
        }
        wasmJsMain.dependencies {
            implementation(securityLibs.cryptography.core)
            implementation(securityLibs.cryptography.provider.webcrypto)
        }
    }
}
