import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
}

kotlin {
    // The sample is an app, not a library: no explicit API mode.
    explicitApi = null

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework { baseName = "SinewSample" }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { binaries.executable() }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.sinewPaging)
            implementation(projects.sinewViewmodel)
            implementation(projects.sinewNetwork)
            implementation(projects.sinewCamouflage)
            implementation(composeLibs.foundation)
        }
    }
}

// Devtools wiring (see docs: DevTools) lives only in the platform entry points.
// The sinew.devtools property picks the real or the no-op artifact for iOS, desktop and web; Android picks it in :sample:androidApp.
val devtools = providers.gradleProperty("sinew.devtools").map(String::toBoolean).orElse(false).get()
kotlin {
    sourceSets {
        iosMain.dependencies { implementation(if (devtools) projects.sinewDevtoolsUi else projects.sinewDevtoolsNoop) }
        jvmMain.dependencies { implementation(if (devtools) projects.sinewDevtoolsUi else projects.sinewDevtoolsNoop) }
        wasmJsMain.dependencies { implementation(if (devtools) projects.sinewDevtoolsUi else projects.sinewDevtoolsNoop) }
    }
}
