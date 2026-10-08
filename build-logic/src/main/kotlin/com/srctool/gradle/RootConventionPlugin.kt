package com.srctool.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

/** Applied to the root project: creates the `srctool { }` extension the other convention plugins read. */
class RootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create<SrctoolExtension>("srctool")
    }
}
