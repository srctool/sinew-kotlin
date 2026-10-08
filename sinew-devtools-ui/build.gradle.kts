plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "DevTools overlay, dashboard and inspector pages."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewDevtools)
            implementation(projects.sinewL10n)
            implementation(composeLibs.foundation)
            implementation(composeLibs.material3)
        }
    }
}
