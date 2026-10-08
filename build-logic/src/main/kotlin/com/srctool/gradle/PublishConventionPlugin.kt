package com.srctool.gradle

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/** Maven Central publishing (Central Portal) with the srctool POM. Signing applies when a key is configured. */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.vanniktech.maven.publish")
        val srctool = rootProject.extensions.getByType<SrctoolExtension>()

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
