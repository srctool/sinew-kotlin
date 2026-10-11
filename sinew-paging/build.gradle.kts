plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "Pager, PagingState, LoadType and the paging strategies."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewModels)
            api(projects.sinewException)
            implementation(kotlinLibs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(projects.sinewTesting)               // ControlledFetch, runTest
        }
    }
}
