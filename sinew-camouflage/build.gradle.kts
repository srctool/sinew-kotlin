plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "PagingState.toCamo() for Camouflage's CamoPagedList."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewPaging)
            api(camouflageLibs.core)
            api(projects.sinewException)                       // Localizer is in toCamo's signature
            implementation(projects.sinewL10n)
        }
        jvmTest.dependencies {
            implementation(composeLibs.ui.test)
            implementation(compose.desktop.currentOs)          // the Compose UI test window needs Skiko's native library
        }
    }
}
