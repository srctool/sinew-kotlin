# Sinew for Kotlin

Sinew is a plug-and-play app architecture: response mapping, results, a coded error model, paging, presentation base classes, an HTTP client with token refresh, secure storage, and developer tools. This repository is the **Kotlin Multiplatform** implementation (Android, iOS, JVM desktop, wasmJs), usable from a plain Android app.

The design and the full documentation live in the umbrella repository, [srctool/sinew](https://github.com/srctool/sinew).

## Modules

| Module | Holds |
|---|---|
| `sinew-models` | `Domain`, `Response`, `Entity`, envelope bases, paging shapes, `ViewState` |
| `sinew-exception` | `AppException` types, handlers, `processCall`, `CrashReporter`, `Localizer` |
| `sinew-l10n` | English and Indonesian for the local error keys (Compose resources) |
| `sinew-paging` | `Pager`, `PagingState`, `LoadType` |
| `sinew-presentation` | `StateEffectHandler`, `EventActionHandler`, `EffectEmitter` |
| `sinew-viewmodel` | AndroidX `ViewModel` bases, `ObserveEffects` |
| `sinew-network` | `SinewHttp` (Ktor), the auth layer, `processApiCall`, retry and polling |
| `sinew-security` | `FieldCipher`, `BiometricVault` |
| `sinew-storage` | `SecureStore`, `KeyValueStore`, `processStorageCall`, `pagedQuery` |
| `sinew-testing` | fakes and test helpers (test-only) |
| `sinew-camouflage` | `PagingState.toCamo()` for Camouflage's `CamoPagedList` |
| `sinew-devtools`, `sinew-devtools-ui`, `sinew-devtools-noop` | developer tools, and their no-op stand-in for production |
| `sinew-bom` | pins every module to one version |

Status: milestone **S0** (skeleton). Each module holds a placeholder until its milestone.

## Build

- JDK 17+, the Android SDK (`local.properties` → `sdk.dir`), Xcode for iOS.
- `./gradlew check checkSinewGraph`: tests, and the dependency-graph check against `gradle/sinew-graph.txt`.
- `./gradlew :sample:shared:run`: the desktop sample. `./gradlew :sample:androidApp:installStagingDebug`: the Android sample.
- iOS: see `sample/iosApp/README.md`.

## Coordinates

`com.srctool.sinew:sinew-<module>`, with `com.srctool.sinew:sinew-bom`.

## License

Apache 2.0, see [LICENSE](LICENSE).
