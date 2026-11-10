// A consumer of the published Sinew artifacts: it resolves them from Maven Local, never from this build's projects.
// Run it with tool/verify-publish.sh, which publishes first.
rootProject.name = "consumer-check"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("com.srctool.sinew") } }   // only Sinew comes from Maven Local
        google()
        mavenCentral()
    }
    versionCatalogs {
        // The same tool versions as the library, so the consumer compiles with the Kotlin the artifacts were built with.
        create("kotlinLibs") { from(files("../../gradle/kotlin.versions.toml")) }
        create("androidxLibs") { from(files("../../gradle/androidx.versions.toml")) }
    }
}
