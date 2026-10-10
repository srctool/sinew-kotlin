plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "SinewException types, handlers, processCall, CrashReporter, Localizer."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewModels)
        }
    }
}
