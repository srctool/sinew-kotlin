# Sinew for Kotlin

[![Maven Central](https://img.shields.io/maven-central/v/com.srctool.sinew/sinew-bom)](https://central.sonatype.com/namespace/com.srctool.sinew)
[![CI](https://github.com/srctool/sinew-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/srctool/sinew-kotlin/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Sinew is a plug-and-play app architecture: response mapping, results, a coded error model, paging, presentation base classes, an HTTP client with token refresh, secure storage, and developer tools. This repository is the **Kotlin Multiplatform** implementation (Android, iOS, JVM desktop, wasmJs), usable from a plain Android app. The same architecture exists for Flutter in [sinew-dart](https://github.com/srctool/sinew-dart).

> **Status:** 1.0.0 is in preparation. The snippets below use `1.0.0`; until it reaches Maven Central, build from source (see [Build](#build)). Every module is released at one version.

## Getting started

Requirements: JDK 17, Kotlin 2.4, Android `minSdk` 24. The targets are Android, iOS (`iosArm64`, `iosSimulatorArm64`), JVM desktop and `wasmJs`.

Add the BOM once, then the modules you need, with no versions:

```kotlin
// build.gradle.kts of a shared module
dependencies {
    implementation(platform("com.srctool.sinew:sinew-bom:1.0.0"))
    implementation("com.srctool.sinew:sinew-models")
    implementation("com.srctool.sinew:sinew-exception")
    implementation("com.srctool.sinew:sinew-network")
    implementation("com.srctool.sinew:sinew-paging")
    implementation("com.srctool.sinew:sinew-viewmodel")
    testImplementation("com.srctool.sinew:sinew-testing")
}
```

In a Kotlin Multiplatform module, put the same lines in the `commonMain` dependencies. `sinew-testing` belongs in tests only.

### Developer tools

Devtools never reach a production build. The app picks one artifact per build:

```kotlin
// the Android app module: one artifact per variant, and a new variant must choose
"productionDebugImplementation"("com.srctool.sinew:sinew-devtools-ui")
"stagingImplementation"("com.srctool.sinew:sinew-devtools-ui")
"productionReleaseImplementation"("com.srctool.sinew:sinew-devtools-noop")
```

On iOS, desktop and web, the convention is a Gradle property that picks the artifact (the sample uses `-Psinew.devtools=true` for the real one, and the no-op without it), so a build that forgets it fails safe. Sinew's CI checks that the sample's production outputs hold no devtools code.

### A first screen

A list that pages, in a view model (from [the sample](sample/shared/src/commonMain/kotlin/com/srctool/sinew/sample/OrderListViewModel.kt)):

```kotlin
class OrderListViewModel(backend: OrderBackend) :
    EventActionViewModel<OrderListState, OrderListEvent, LoadOrders, Nothing>(OrderListState()) {
    private val pager = Pager.offset<Order>(itemKey = { it.id }) { page, _ -> backend.orders(page) }

    override fun mapEventToAction(event: OrderListEvent): LoadOrders? = when (event) {
        OrderListEvent.Opened -> LoadOrders(LoadType.Initial)
        OrderListEvent.EndReached -> if (currentState.orders.canLoadMore) LoadOrders(LoadType.More) else null
        OrderListEvent.RetryTapped -> LoadOrders(currentState.orders.retryType)
        OrderListEvent.Refreshed -> LoadOrders(LoadType.Refresh)
    }

    override suspend fun onAction(action: LoadOrders) {
        pager.load(action.type, currentState.orders) { next -> setState { copy(orders = next) } }
    }
}
```

`sinew-camouflage` turns the `PagingState` into [Camouflage](https://github.com/srctool/camouflage-kotlin)'s `CamoPagedList`: skeleton, items, load more, retry, refresh and the end of the list.

## Modules

Coordinates are `com.srctool.sinew:sinew-<module>`.

| Module | Holds |
|---|---|
| `sinew-models` | `Domain`, `Response`, `Entity`, envelope bases, paging shapes, `ViewState` |
| `sinew-exception` | `SinewException` types, handlers, `processCall`, `CrashReporter`, `Localizer` |
| `sinew-l10n` | English and Indonesian for the local error keys (Compose resources) |
| `sinew-paging` | `Pager`, `PagingState`, `LoadType` |
| `sinew-presentation` | `StateEffectHandler`, `EventActionHandler`, `EffectEmitter` |
| `sinew-viewmodel` | AndroidX `ViewModel` bases, `ObserveEffects` |
| `sinew-network` | `SinewHttp` (Ktor), the auth layer, `processApiCall`, retry and polling |
| `sinew-security` | `FieldCipher` (AES-256-GCM), `BiometricVault` |
| `sinew-storage` | `SecureStore`, `KeyValueStore`, `processStorageCall`, `pagedQuery` |
| `sinew-testing` | fakes and test helpers (test-only) |
| `sinew-camouflage` | `PagingState.toCamo()` for Camouflage's `CamoPagedList` |
| `sinew-devtools` | recorders, redaction, fault injection, host override and replay |
| `sinew-devtools-ui` | the overlay and its pages |
| `sinew-devtools-noop` | the same API with nothing behind it, for production builds |
| `sinew-bom` | pins every module to one version |

## Documentation

- **Usage docs:** [sinew.srctool.com](https://sinew.srctool.com), for developers using Sinew in an app.
- **API reference:** attached to each [GitHub release](https://github.com/srctool/sinew-kotlin/releases) (one folder per module).
- **Design and decisions:** the [Guide](https://sinew-dev.srctool.com/guide) and the [roadmap](https://sinew-dev.srctool.com/guide/project/roadmap), in the umbrella repository [srctool/sinew](https://github.com/srctool/sinew).
- **Changes:** [CHANGELOG.md](CHANGELOG.md).

## Build

- JDK 17+, the Android SDK (`local.properties` → `sdk.dir`), Xcode for iOS.
- `./gradlew check checkSinewGraph`: tests, and the dependency-graph check against `gradle/sinew-graph.txt`.
- `./gradlew :sample:shared:run`: the desktop sample. `./gradlew :sample:androidApp:installStagingDebug`: the Android sample.
- iOS: see `sample/iosApp/README.md`.

See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License

Apache 2.0, see [LICENSE](LICENSE).
