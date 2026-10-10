plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
    alias(kotlinLibs.plugins.serialization)
}

description = "Domain, Remote, Entity, envelope bases, paging shapes, ViewState, Result."

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")   // PlatformContext, the one public expect class
    }
    sourceSets {
        commonMain.dependencies {
            api(kotlinLibs.serialization.core)               // DomainNotParsed is a KSerializer
        }
        commonTest.dependencies {
            implementation(kotlinLibs.serialization.json)
        }
    }
}
