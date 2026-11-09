plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "DevTools capture: recorders, redaction, fault rules, host override."

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()    // the no-op artifact's API must match this one (sinew-devtools-noop)
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewNetwork)
            api(projects.sinewStorage)
            implementation(kotlinLibs.serialization.json)       // walking JSON bodies to redact them
            implementation(kotlinLibs.coroutines.core)          // the recorders' StateFlows, DevTools.coroutineHandler
        }
        commonTest.dependencies {
            implementation(projects.sinewTesting)               // RecordingCrashReporter
            implementation(kotlinLibs.coroutines.test)
        }
    }
}
