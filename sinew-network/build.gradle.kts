plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
    alias(kotlinLibs.plugins.serialization)            // test DTOs
}

description = "SinewHttp, the auth layer, processApiCall, retry and polling, NetworkChecker."

kotlin {
    android {
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }   // NetworkChecker on a device
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewException)
            api(ktorLibs.client.core)
            implementation(ktorLibs.client.content.negotiation)
            implementation(ktorLibs.serialization.json)
            implementation(kotlinLibs.serialization.json)
            implementation(kotlinLibs.datetime)
        }
        commonTest.dependencies {
            implementation(projects.sinewTesting)               // FakeTokenSource
            implementation(ktorLibs.client.mock)
            implementation(kotlinLibs.coroutines.test)
        }
        androidMain.dependencies { implementation(ktorLibs.client.okhttp) }
        getByName("androidDeviceTest").dependencies {
            implementation(androidxLibs.test.runner)
            implementation(androidxLibs.test.ext.junit)
            implementation(kotlin("test"))
            implementation(kotlinLibs.coroutines.test)
        }
        jvmMain.dependencies { implementation(ktorLibs.client.okhttp) }
        jvmTest.dependencies {
            implementation(ktorLibs.okhttp.tls)                 // a CA and server certificates, in memory
            implementation(ktorLibs.okhttp.mockwebserver)       // a real HTTPS server
        }
        iosMain.dependencies { implementation(ktorLibs.client.darwin) }
        wasmJsMain.dependencies { implementation(ktorLibs.client.js) }
    }
}
