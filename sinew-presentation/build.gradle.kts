plugins {
    id("com.srctool.kmp.library")
    id("com.srctool.publish")
}

description = "StateEffectHandler, EventActionHandler, EffectEmitter (framework-neutral)."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(kotlinLibs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(projects.sinewTesting)               // Turbine, runTest, Pager and ControlledFetch for the sample handler
        }
    }
}
