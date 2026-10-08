// The srctool convention plugins, shared in spirit with Camouflage's build logic.
// Plugin ids are generic (com.srctool.*) so this folder can later be replaced by the published plugins.
plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(kotlinLibs.gradle.plugin)
    compileOnly(kotlinLibs.compose.compiler.gradle.plugin)
    compileOnly(androidxLibs.gradle.plugin)
    compileOnly(composeLibs.gradle.plugin)
    compileOnly(toolLibs.kover.gradle.plugin)
    compileOnly(toolLibs.vanniktech.publish.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("root") {
            id = "com.srctool.root"
            implementationClass = "com.srctool.gradle.RootConventionPlugin"
        }
        register("kmpLibrary") {
            id = "com.srctool.kmp.library"
            implementationClass = "com.srctool.gradle.KmpLibraryConventionPlugin"
        }
        register("compose") {
            id = "com.srctool.compose"
            implementationClass = "com.srctool.gradle.ComposeConventionPlugin"
        }
        register("publish") {
            id = "com.srctool.publish"
            implementationClass = "com.srctool.gradle.PublishConventionPlugin"
        }
    }
}
