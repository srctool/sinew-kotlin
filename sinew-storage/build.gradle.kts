plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "SecureStore, KeyValueStore, processStorageCall, pagedQuery."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewException)
            api(projects.sinewSecurity)
            implementation(kotlinLibs.coroutines.core)
        }
        androidMain.dependencies { implementation(androidxLibs.datastore.preferences.core) }
        jvmMain.dependencies { implementation(androidxLibs.datastore.preferences.core) }
        iosMain.dependencies { implementation(androidxLibs.datastore.preferences.core) }
    }
}
