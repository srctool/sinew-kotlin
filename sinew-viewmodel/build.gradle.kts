plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "StateEffectViewModel, EventActionViewModel, ObserveEffects."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewPresentation)
            api(projects.sinewModels)
            implementation(projects.sinewException)
            api(androidxLibs.lifecycle.viewmodel)
            implementation(androidxLibs.lifecycle.runtime.compose)
        }
        commonTest.dependencies {
            implementation(projects.sinewTesting)               // runTest, Turbine
        }
        jvmTest.dependencies {
            implementation(composeLibs.ui.test)                 // runComposeUiTest for ObserveEffects
            implementation(compose.desktop.currentOs)          // Skiko for Compose on the JVM
            implementation(kotlinLibs.coroutines.swing)         // Dispatchers.Main, as in a Compose desktop app
        }
    }
}
