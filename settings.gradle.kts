rootProject.name = "sinew-kotlin"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("kotlinLibs") { from(files("gradle/kotlin.versions.toml")) }        // kotlin, coroutines, serialization, datetime
        create("ktorLibs") { from(files("gradle/ktor.versions.toml")) }            // ktor core, engines, content negotiation, mock
        create("androidxLibs") { from(files("gradle/androidx.versions.toml")) }    // lifecycle, datastore, biometric, AGP
        create("composeLibs") { from(files("gradle/compose.versions.toml")) }      // compose multiplatform, resources, material3
        create("securityLibs") { from(files("gradle/security.versions.toml")) }    // tink, cryptography-kotlin
        create("toolLibs") { from(files("gradle/tools.versions.toml")) }           // turbine, kover, vanniktech
    }
}

include(
    ":sinew-models", ":sinew-exception", ":sinew-l10n", ":sinew-paging", ":sinew-presentation", ":sinew-viewmodel",
    ":sinew-network", ":sinew-security", ":sinew-storage", ":sinew-testing", ":sinew-camouflage",
    ":sinew-devtools", ":sinew-devtools-ui", ":sinew-devtools-noop", ":sinew-bom",
    ":sample:shared", ":sample:androidApp",
)
