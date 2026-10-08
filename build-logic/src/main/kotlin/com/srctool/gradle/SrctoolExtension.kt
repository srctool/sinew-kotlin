package com.srctool.gradle

import org.gradle.api.provider.Property

/** Project-specific values for the srctool convention plugins, set once in each project's root build. */
abstract class SrctoolExtension {
    /** The Android namespace prefix; a module's namespace is the prefix plus its name without the project prefix. */
    abstract val namespacePrefix: Property<String>

    /** The prefix every module name starts with ("sinew-"), dropped when building namespaces and packages. */
    abstract val modulePrefix: Property<String>

    /** The Maven group, e.g. "com.srctool.sinew". */
    abstract val groupId: Property<String>

    /** The public repository URL, used in POMs. */
    abstract val repoUrl: Property<String>
}
