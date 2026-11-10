// The Kotlin samples of the usage docs, as real code: CI compiles them, so a documented call that stops existing fails
// the build. Each sample sits between `// region <name>` and `// endregion` lines; the docs site copies the regions.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(kotlinLibs.plugins.serialization)
}

dependencies {
    implementation(projects.sinewTesting)                // re-exports models, exception, paging, network, storage, security and Ktor's mock engine
    implementation(projects.sinewViewmodel)
    implementation(kotlinLibs.serialization.json)
    implementation(kotlin("test-junit"))
}
