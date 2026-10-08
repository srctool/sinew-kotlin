plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "SinewHttp, the auth layer, processApiCall, retry and polling, NetworkChecker."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewException)
            api(ktorLibs.client.core)
            implementation(ktorLibs.client.content.negotiation)
            implementation(ktorLibs.serialization.json)
            implementation(kotlinLibs.serialization.json)
            implementation(kotlinLibs.datetime)
        }
        androidMain.dependencies { implementation(ktorLibs.client.okhttp) }
        jvmMain.dependencies { implementation(ktorLibs.client.okhttp) }
        iosMain.dependencies { implementation(ktorLibs.client.darwin) }
        wasmJsMain.dependencies { implementation(ktorLibs.client.js) }
    }
}
