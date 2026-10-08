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
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.srctool.sinew.l10n.resources"
}
