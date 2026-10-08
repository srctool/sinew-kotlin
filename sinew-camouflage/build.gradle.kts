plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "PagingState.toCamo() for Camouflage's CamoPagedList. The camouflage-core dependency is added at S5."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewPaging)
            implementation(projects.sinewException)
            implementation(projects.sinewL10n)
        }
    }
}
