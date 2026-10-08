plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "DevTools capture: recorders, redaction, fault rules, host override."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewNetwork)
            api(projects.sinewStorage)
        }
    }
}
