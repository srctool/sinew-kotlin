import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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
    alias(toolLibs.plugins.dokka) apply false
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
            // Only the buckets a build file declares into: each source set's api/implementation/compileOnly/runtimeOnly,
            // main and test alike. Plugin-internal configurations can pick up project dependencies depending on which
            // other tasks are in the graph (e.g. iOS simulator tests), which made the result vary per invocation.
            p.extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets.flatMap { sourceSet ->
                val isCommonTest = sourceSet.name == "commonTest"
                listOf(sourceSet.apiConfigurationName, sourceSet.implementationConfigurationName, sourceSet.compileOnlyConfigurationName, sourceSet.runtimeOnlyConfigurationName)
                    .mapNotNull(p.configurations::findByName)
                    .flatMap { c -> c.dependencies.withType(ProjectDependency::class.java) }
                    .map { p.name to it.path.removePrefix(":") }
                    .filter { (from, to) -> to.startsWith("sinew-") && to != from }   // a module's tests depend on the module itself
                    // sinew-testing is allowed in commonTest only, so every target's tests share it; from main or a
                    // platform's tests it fails.
                    .filterNot { (_, to) -> isCommonTest && to == "sinew-testing" }
            }
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

// Production builds depend on sinew-devtools-noop instead of the real devtools, so it must declare exactly what
// sinew-devtools and sinew-devtools-ui declare together. Each module's checkKotlinAbi keeps its own dump current; this
// compares the dumps (JVM, Android and klib), line by line, ignoring the header comments that name the library and
// what the Compose compiler generates.
tasks.register("checkDevtoolsNoopAbi") {
    group = "verification"
    description = "Checks that sinew-devtools-noop's ABI equals sinew-devtools' and sinew-devtools-ui's together."
    val real = listOf("sinew-devtools", "sinew-devtools-ui")
    val noop = "sinew-devtools-noop"
    (real + noop).forEach { dependsOn(":$it:checkKotlinAbi") }
    val root = layout.projectDirectory
    fun dump(module: String, kind: String) = when (kind) {
        "klib" -> root.file("$module/api/$module.klib.api").asFile
        else -> root.file("$module/api/$kind/$module.api").asFile
    }
    val kinds = listOf("jvm", "android", "klib")
    kinds.forEach { kind -> (real + noop).forEach { inputs.file(dump(it, kind)) } }
    doLast {
        // What the Compose compiler generates, not API: the lambda holders (`ComposableSingletons…`, one per file with
        // lambdas) and the stability fields Compose adds to each class in a module built with it (`$stable`, `$stableprop`) (the no-op's capture
        // classes are; the real sinew-devtools isn't).
        fun lines(module: String, kind: String): List<String> {
            val blocks = dump(module, kind).readText().split(Regex("\\n\\s*\\n"))
            return blocks.filterNot { "ComposableSingletons" in it.lineSequence().firstOrNull { l -> !l.startsWith("//") }.orEmpty() }
                .flatMap { it.lines() }
                .filter { it.isNotBlank() && !it.trimStart().startsWith("//") && "\$stable" !in it }
        }
        val problems = kinds.mapNotNull { kind ->
            val expected = real.flatMap { lines(it, kind) }.groupingBy { it }.eachCount()
            val actual = lines(noop, kind).groupingBy { it }.eachCount()
            if (expected == actual) return@mapNotNull null
            val missing = expected.filter { (line, n) -> (actual[line] ?: 0) < n }.keys
            val extra = actual.filter { (line, n) -> (expected[line] ?: 0) < n }.keys
            "$kind: missing from the no-op ${missing.size} line(s), extra in it ${extra.size}\n" +
                missing.take(15).joinToString("") { "  - $it\n" } + extra.take(15).joinToString("") { "  + $it\n" }
        }
        check(problems.isEmpty()) { "sinew-devtools-noop's ABI differs from the real devtools':\n" + problems.joinToString("\n") }
    }
}

// The gating proof (see the docs: DevTools, Gating): the sample's production outputs carry no devtools code beyond the
// no-op. Each output is searched for `com.srctool.sinew.devtools` names: class names in an APK's dex and a jar, `kclass:`
// and `kfun:` symbols in the iOS framework, Compose's trace keys in the wasm. Each name is cut to its top-level
// declaration, and any the no-op doesn't declare (by its ABI dumps) means real devtools code. A build that should carry
// the real devtools is checked the opposite way, which shows the search can see it: the Android staging release always,
// and the iOS, desktop and web outputs with -Psinew.devtools=true.
val devtoolsGatingExpectReal = providers.gradleProperty("sinew.devtools").map(String::toBoolean).orElse(false)

fun registerDevToolsGatingCheck(target: String, build: String, outputs: List<Pair<String, Provider<Boolean>>>) =
    tasks.register("checkDevToolsGating$target") {
        group = "verification"
        description = "Checks that the sample's $target output carries devtools code only where it should."
        dependsOn(build.split(' '))
        val noopApi = layout.projectDirectory.dir("sinew-devtools-noop/api").asFile
        val checked = outputs.map { (path, expectReal) -> layout.projectDirectory.dir(path).asFile to expectReal }
        val root = layout.projectDirectory.asFile
        doLast {
            val declared = Regex("""com\.srctool\.sinew\.devtools(?:\.ui)?/(\w+)|class com/srctool/sinew/devtools/(?:ui/)?([\w$]+)""")
            val allowed = noopApi.walk().filter { it.isFile && it.extension == "api" }
                .flatMap { declared.findAll(it.readText()) }
                .flatMap { m -> (m.groupValues[1] + "$" + m.groupValues[2]).split('$') }
                .filter(String::isNotEmpty).toSet()
            // The bytes searched: a dex or a jar's entry names from inside a zip, any other file as it is.
            fun contents(file: File): Sequence<String> = when (file.extension) {
                "apk", "jar" -> java.util.zip.ZipFile(file).use { zip ->
                    zip.entries().asSequence().flatMap { e ->
                        if (e.name.endsWith(".dex")) sequenceOf(e.name, zip.getInputStream(e).readBytes().toString(Charsets.ISO_8859_1))
                        else sequenceOf(e.name)
                    }.toList().asSequence()
                }
                else -> sequenceOf(file.readBytes().toString(Charsets.ISO_8859_1))
            }
            val name = Regex("""com[./]srctool[./]sinew[./]devtools(?:[./]ui)?[.#/$](\w+?)(?:_\d+)?(?:\$(\w+))?\b""")
            val problems = checked.mapNotNull { (dir, expectReal) ->
                val files = dir.walk().filter { it.isFile && it.extension in setOf("apk", "jar", "wasm", "") && !it.isHidden }.toList()
                if (files.isEmpty()) return@mapNotNull "$dir: no build output found"
                val real = files.flatMap(::contents).flatMap { name.findAll(it) }
                    .map { m -> if (m.groupValues[1] == "ComposableSingletons") m.groupValues[2] else m.groupValues[1] }
                    .filter { it.isNotEmpty() && it != "ui" && it !in allowed }.toSortedSet()
                val where = dir.relativeTo(root)
                when {
                    expectReal.get() && real.isEmpty() -> "$where should carry the real devtools, but no devtools code was found in it"
                    !expectReal.get() && real.isNotEmpty() -> "$where carries devtools code the no-op doesn't declare: " + real.take(15).joinToString()
                    else -> null.also { println("$where: ${if (expectReal.get()) "real devtools (${real.size} names)" else "no-op only"}") }
                }
            }
            check(problems.isEmpty()) { "Devtools gating failed:\n" + problems.joinToString("\n") { "  $it" } }
        }
    }

val noReal = provider { false }
val gatingChecks = listOfNotNull(
    registerDevToolsGatingCheck(
        "Android", ":sample:androidApp:assembleProductionRelease :sample:androidApp:assembleStagingRelease",
        listOf("sample/androidApp/build/outputs/apk/production/release" to noReal, "sample/androidApp/build/outputs/apk/staging/release" to provider { true }),
    ),
    registerDevToolsGatingCheck("Desktop", ":sample:shared:packageUberJarForCurrentOS", listOf("sample/shared/build/compose/jars" to devtoolsGatingExpectReal)),
    registerDevToolsGatingCheck("Web", ":sample:shared:wasmJsBrowserDistribution", listOf("sample/shared/build/dist/wasmJs/productionExecutable" to devtoolsGatingExpectReal)),
    // The device framework, as an app ships it; Kotlin/Native builds Apple targets only on macOS.
    if (System.getProperty("os.name").startsWith("Mac")) registerDevToolsGatingCheck(
        "Ios", ":sample:shared:linkReleaseFrameworkIosArm64", listOf("sample/shared/build/bin/iosArm64/releaseFramework" to devtoolsGatingExpectReal),
    ) else null,
)

tasks.register("checkDevToolsGating") {
    group = "verification"
    description = "Checks every sample output this host builds for devtools code where it shouldn't be."
    dependsOn(gatingChecks)
}
