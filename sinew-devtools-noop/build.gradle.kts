plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "The DevTools API with no behavior, for production builds."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewNetwork)
            api(projects.sinewStorage)
        }
    }
}
