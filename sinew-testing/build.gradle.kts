plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "Fakes and test helpers. A test-only dependency."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewModels)
            api(projects.sinewException)
            api(projects.sinewPaging)
            api(projects.sinewNetwork)
            api(projects.sinewStorage)
            api(projects.sinewSecurity)
            api(ktorLibs.client.mock)
            api(kotlinLibs.coroutines.test)
            api(toolLibs.turbine)
        }
    }
}
