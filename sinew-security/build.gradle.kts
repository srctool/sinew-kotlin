import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "FieldCipher and BiometricVault on vetted crypto per platform."

kotlin {
    android {
        // The device test joins the "test" tree, so the commonTest suite also runs on a device, against the Keystore.
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    // tinkMain: Tink's code for Android and the JVM, compiled against the JVM artifact; each target ships its own.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("tink") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
    sourceSets {
        getByName("tinkMain").dependencies { compileOnly(securityLibs.tink) }
        commonMain.dependencies {
            api(projects.sinewModels)                           // PlatformContext, nothing else
            implementation(kotlinLibs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlinLibs.coroutines.test)
        }
        androidMain.dependencies {
            implementation(securityLibs.tink.android)
            implementation(androidxLibs.biometric)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(androidxLibs.test.runner)
            implementation(androidxLibs.test.ext.junit)
            implementation(kotlin("test"))
            implementation(kotlinLibs.coroutines.test)
        }
        jvmMain.dependencies { implementation(securityLibs.tink) }
        iosMain.dependencies {
            implementation(securityLibs.cryptography.core)
            implementation(securityLibs.cryptography.provider.cryptokit)
        }
        // wasmJs calls Web Crypto directly: cryptography-kotlin's WebCrypto keys are always extractable,
        // and FieldCipher keeps a non-extractable key in IndexedDB.
    }
}
