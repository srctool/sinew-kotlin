plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "English and Indonesian translations of the local error keys (Compose resources)."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewException)
            implementation(composeLibs.components.resources)
            implementation(kotlinLibs.coroutines.core)        // the localizer's request channel
        }
        commonTest.dependencies {
            implementation(kotlinLibs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)          // the resource environment needs Skiko's native library on the JVM
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.srctool.sinew.l10n.resources"
}
