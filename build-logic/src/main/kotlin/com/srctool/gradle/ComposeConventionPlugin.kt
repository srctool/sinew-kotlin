package com.srctool.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

/** Compose Multiplatform for the few Sinew modules that need it (viewmodel, l10n, camouflage glue, devtools UI). */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val compose = extensions.getByType<VersionCatalogsExtension>().named("composeLibs")
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                implementation(compose.findLibrary("runtime").get())
            }

            // Compose UI browser tests load Skiko only through a webpack bundle, which needs an executable binary;
            // without one, checkComposeUiTestConfigurationForWasmJs fails the browser tests (CMP-4906).
            // The published klib comes from the main compilation, so this adds link tasks but doesn't change it.
            targets.withType<KotlinJsIrTarget>().configureEach { binaries.executable() }
        }
    }
}
