plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

description = "Pins every Sinew module to one version."

dependencies {
    constraints {
        rootProject.subprojects
            .filter { it.name.startsWith("sinew-") && it.name != project.name }
            .forEach { api(it) }
    }
}
