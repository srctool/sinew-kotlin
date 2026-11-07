plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.srctool.sinew.sample"
    compileSdk = androidxLibs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.srctool.sinew.sample"
        minSdk = androidxLibs.versions.minSdk.get().toInt()
        targetSdk = androidxLibs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "env"
    productFlavors {
        create("staging") { dimension = "env" }
        create("production") { dimension = "env" }
    }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").kotlin.directories += "../shared/src/uiTest/kotlin"   // the list screen's full cycle
}

// Flavor + build-type configurations aren't created by AGP up front, so create the ones used below.
listOf("productionDebugImplementation", "productionReleaseImplementation").forEach { configurations.maybeCreate(it) }

dependencies {
    implementation(projects.sample.shared)
    implementation(androidxLibs.activity.compose)
    androidTestImplementation(projects.sinewModels)
    androidTestImplementation(projects.sinewException)
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(composeLibs.ui.test)
    androidTestImplementation(androidxLibs.test.runner)
    androidTestImplementation(androidxLibs.test.ext.junit)
    debugImplementation(composeLibs.ui.test.manifest)
    // Devtools: one artifact per variant (see docs: DevTools). A new variant must be assigned one explicitly.
    "productionDebugImplementation"(projects.sinewDevtoolsUi)
    "stagingImplementation"(projects.sinewDevtoolsUi)
    "productionReleaseImplementation"(projects.sinewDevtoolsNoop)
}
