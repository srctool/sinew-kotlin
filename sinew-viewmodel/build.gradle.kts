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
    }
}
