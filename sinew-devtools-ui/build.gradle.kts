plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.compose")
    id("com.srctool.publish")
}

description = "DevTools overlay, dashboard and inspector pages."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.sinewDevtools)
            implementation(projects.sinewL10n)
            implementation(composeLibs.foundation)
            implementation(composeLibs.material3)
            implementation(composeLibs.navigationevent.compose)      // system Back walks the dashboard's own back stack
        }
        commonTest.dependencies {
            implementation(projects.sinewTesting)                   // FakeKeyValueStore, FakeSecureStore, mockHttp
            implementation(kotlinLibs.coroutines.test)
            implementation(composeLibs.ui.test)
            implementation(ktorLibs.client.mock)                     // a real client for Replay
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)               // the Compose UI test window needs Skiko's native library
            implementation(composeLibs.roborazzi.compose.desktop)
        }
        getByName("androidHostTest").dependencies {
            implementation(androidxLibs.robolectric)
            implementation(androidxLibs.test.ext.junit)
            implementation(composeLibs.ui.test.junit4.android)
            implementation(composeLibs.ui.test.manifest)
            implementation(composeLibs.roborazzi)
            implementation(composeLibs.roborazzi.compose)
        }
    }
}

// Screenshots live under screenshots/<os>/<platform>/, since fonts render differently per OS. A test compares against
// the reference for the OS it runs on and fails on a difference; where there's none yet, it records one (CI uploads
// them). `-Psinew.screenshots=record` records every image again.
val screenshots = providers.gradleProperty("sinew.screenshots").orElse("verify")
tasks.withType<Test>().configureEach {
    systemProperty("sinew.screenshots", screenshots.get())
    systemProperty("sinew.screenshots.dir", layout.projectDirectory.dir("screenshots").asFile.absolutePath)
    inputs.files(fileTree("screenshots")).withPropertyName("screenshots")
}
// The shared Compose UI tests need a window: they run on the JVM, iOS and wasm. Android's run under Robolectric, in
// androidHostTest's own classes.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    (this as Test).filter.excludeTestsMatching("com.srctool.sinew.devtools.ui.SinewDevToolsTest")
}
