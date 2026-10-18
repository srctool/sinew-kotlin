import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "SecureStore, KeyValueStore, processStorageCall, pagedQuery."

kotlin {
    // dataStoreMain: the DataStore stores, shared by Android, iOS and the JVM (DataStore has no wasm target).
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("dataStore") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
                group("ios") { withIos() }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewException)
            api(projects.sinewSecurity)
            implementation(kotlinLibs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlinLibs.coroutines.test)
        }
        getByName("dataStoreMain").dependencies { implementation(androidxLibs.datastore.preferences.core) }
    }
}
