plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "DevTools capture: recorders, redaction, fault rules, host override."

kotlin {
    // The publish convention validates the ABI. The no-op artifact's API must match this one (checkDevtoolsNoopAbi).
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
