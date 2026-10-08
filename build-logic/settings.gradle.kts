rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("kotlinLibs") { from(files("../gradle/kotlin.versions.toml")) }
        create("androidxLibs") { from(files("../gradle/androidx.versions.toml")) }
        create("composeLibs") { from(files("../gradle/compose.versions.toml")) }
        create("toolLibs") { from(files("../gradle/tools.versions.toml")) }
    }
}
