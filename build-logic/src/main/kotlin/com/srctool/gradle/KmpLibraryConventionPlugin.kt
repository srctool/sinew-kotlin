package com.srctool.gradle

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A Kotlin Multiplatform library on every Sinew target: Android, iOS (arm64 + simulator arm64), JVM desktop, wasmJs.
 * Explicit API mode is on, and coverage (Kover) is applied.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        val srctool = rootProject.extensions.getByType<SrctoolExtension>()
        val android = extensions.getByType<VersionCatalogsExtension>().named("androidxLibs")
        val suffix = name.removePrefix(srctool.modulePrefix.get()).replace('-', '.')

        extensions.configure<KotlinMultiplatformExtension> {
            explicitApi()

            // AGP's KMP library target; its DSL name changed between AGP versions ("androidLibrary", then "android").
            val kmpExtensions = (this as ExtensionAware).extensions
            val androidTargetName = listOf("android", "androidLibrary").first { kmpExtensions.findByName(it) != null }
            kmpExtensions.configure<KotlinMultiplatformAndroidLibraryTarget>(androidTargetName) {
                namespace = srctool.namespacePrefix.get() + "." + suffix
                compileSdk = android.findVersion("compileSdk").get().requiredVersion.toInt()
                minSdk = android.findVersion("minSdk").get().requiredVersion.toInt()
                withHostTest {}                                  // commonTest also runs as Android host (JVM) tests
            }

            iosArm64()
            iosSimulatorArm64()
            jvm()
            @OptIn(ExperimentalWasmDsl::class)
            wasmJs { browser() }

            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
