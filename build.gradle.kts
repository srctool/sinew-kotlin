import org.gradle.api.artifacts.ProjectDependency

plugins {
    id("com.srctool.root")
    alias(kotlinLibs.plugins.multiplatform) apply false
    alias(kotlinLibs.plugins.compose.compiler) apply false
    alias(kotlinLibs.plugins.serialization) apply false
    alias(androidxLibs.plugins.android.application) apply false
    alias(androidxLibs.plugins.android.kmp.library) apply false
    alias(composeLibs.plugins.compose.multiplatform) apply false
    alias(toolLibs.plugins.kover) apply false
    alias(toolLibs.plugins.vanniktech.publish) apply false
}

srctool {
    namespacePrefix = "com.srctool.sinew"
    modulePrefix = "sinew-"
    groupId = providers.gradleProperty("GROUP").get()
    repoUrl = "https://github.com/srctool/sinew-kotlin"
}

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

// Fails on any project-to-project dependency between Sinew modules that isn't listed in gradle/sinew-graph.txt.
// The allow-list mirrors the dependency graph in the docs, so a leaked edge can't ship unnoticed.
tasks.register("checkSinewGraph") {
    group = "verification"
    description = "Checks Sinew module dependencies against gradle/sinew-graph.txt."
    val allowed = layout.projectDirectory.file("gradle/sinew-graph.txt").asFile.readLines()
        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .map { it.split("->").map(String::trim).let { (from, to) -> from to to } }.toSet()
    val edges = provider {
        subprojects.filter { it.name.startsWith("sinew-") && it.name != "sinew-bom" }.flatMap { p ->
            p.configurations.flatMap { c -> c.dependencies.withType(ProjectDependency::class.java) }
                .map { p.name to it.path.removePrefix(":") }
                .filter { (from, to) -> to.startsWith("sinew-") && to != from }   // a module's tests depend on the module itself
        }.toSet()
    }
    inputs.property("edges", edges.map { it.map { (a, b) -> "$a->$b" }.sorted() })
    doLast {
        val unexpected = edges.get() - allowed
        check(unexpected.isEmpty()) {
            "Dependencies not allowed by gradle/sinew-graph.txt:\n" + unexpected.joinToString("\n") { "  ${it.first} -> ${it.second}" }
        }
    }
}
