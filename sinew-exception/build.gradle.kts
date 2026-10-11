plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "SinewException types, handlers, processCall, CrashReporter, Localizer."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewModels)
            implementation(kotlinLibs.coroutines.core)        // processCall checks the caller's job before classifying a CancellationException
        }
        commonTest.dependencies {
            implementation(kotlinLibs.coroutines.test)
        }
    }
}
