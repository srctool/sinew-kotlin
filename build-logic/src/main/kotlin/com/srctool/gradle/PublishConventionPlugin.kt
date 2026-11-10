package com.srctool.gradle

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * Maven Central publishing (Central Portal) with the srctool POM. Kotlin libraries also get Dokka, which reports every
 * public declaration without KDoc and fails the build on one, so the API reference has no gaps (a `Module.md` in the
 * module is its module page), and ABI validation: a published module's public API is dumped under `api/`, and
 * `checkKotlinAbi` (run by `check`) fails on any change until `updateKotlinAbi` is run and the dump committed.
 * Signing applies when a key is configured.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.vanniktech.maven.publish")
        val srctool = rootProject.extensions.getByType<SrctoolExtension>()

        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(ExperimentalAbiValidation::class)
                abiValidation()
            }
            pluginManager.apply("org.jetbrains.dokka")
            extensions.configure<DokkaExtension> {
                moduleName.set(project.name)
                val moduleDoc = layout.projectDirectory.file("Module.md").asFile
                // A module may opt out of the check with `extra["sinew.undocumentedAllowed"] = true`, and must then say
                // why on its module page: only an empty stand-in, whose API is documented by the module it mirrors.
                val checked = provider { !target.extra.has("sinew.undocumentedAllowed") }
                dokkaSourceSets.configureEach {
                    reportUndocumented.set(checked)
                    if (moduleDoc.exists()) includes.from(moduleDoc)
                }
                dokkaPublications.configureEach { failOnWarning.set(true) }
            }
        }

        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            // Coordinates come from the GROUP and VERSION_NAME Gradle properties; the artifact id is the module name.
            pom {
                name.set(project.name)
                description.set(provider { project.description ?: project.name })
                url.set(srctool.repoUrl)
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm { url.set(srctool.repoUrl) }
                developers {
                    developer {
                        id.set("srctool")
                        name.set("SRC Tool")
                        email.set("contact@srctool.com")
                    }
                }
            }
        }
    }
}
