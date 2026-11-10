plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "The DevTools API with no behavior, for production builds."

// Its API is the real devtools' (checked by `checkDevtoolsNoopAbi`), documented there; Module.md says so.
extra["sinew.undocumentedAllowed"] = true

kotlin {
    // The publish convention validates the ABI, which must equal sinew-devtools' and sinew-devtools-ui's together:
    // `checkDevtoolsNoopAbi` compares them.
    sourceSets {
        commonMain.dependencies {
            // The real modules' API names these types, so the no-op exposes them the same way.
            api(projects.sinewNetwork)
            api(projects.sinewStorage)
            api(kotlinLibs.coroutines.core)
            api(composeLibs.foundation)
        }
    }
}
