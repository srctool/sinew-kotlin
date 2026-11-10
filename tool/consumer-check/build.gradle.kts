// A fresh Kotlin Multiplatform consumer on all four targets. Its only Sinew dependency is the BOM, and every module
// is named without a version, as a user's build would. Its code is the usage docs' samples (sample/docs), so the
// same code the docs show also compiles against the published artifacts.
plugins {
    alias(kotlinLibs.plugins.multiplatform)
    alias(kotlinLibs.plugins.serialization)
    alias(androidxLibs.plugins.android.kmp.library)
}

val sinewVersion = providers.gradleProperty("sinew.version").orNull
    ?: error("Pass the published version: -Psinew.version=<version> (tool/verify-publish.sh does)")

val modules = listOf(
    "models", "exception", "l10n", "paging", "presentation", "viewmodel", "network", "security", "storage",
    "camouflage", "devtools", "devtools-ui", "devtools-noop",
)

kotlin {
    // AGP's KMP library target; its DSL name changed between AGP versions ("androidLibrary", then "android").
    val androidTargetName = listOf("android", "androidLibrary").first { extensions.findByName(it) != null }
    extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>(androidTargetName) {
        namespace = "com.srctool.sinew.consumer"
        compileSdk = androidxLibs.versions.compileSdk.get().toInt()
        minSdk = androidxLibs.versions.minSdk.get().toInt()
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain {
            kotlin.srcDir("../../sample/docs/src/main/kotlin")
            dependencies {
                implementation(project.dependencies.platform("com.srctool.sinew:sinew-bom:$sinewVersion"))
                modules.forEach { implementation("com.srctool.sinew:sinew-$it") }
                implementation(kotlinLibs.serialization.json)
            }
        }
        commonTest.dependencies {
            implementation("com.srctool.sinew:sinew-testing")   // test-only, as its docs say
        }
    }
}
