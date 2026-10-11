# Changelog

All notable changes to Sinew for Kotlin are documented here. Every module shares one version (lockstep).

The format is based on Keep a Changelog, and this project adheres to Semantic Versioning.

## [Unreleased]
- S1 `sinew-l10n`: `SinewStrings` (keys, suspending `resolve` for a key, arguments and resource environment) and `rememberSinewLocalizer` (the app's strings first, then Sinew's from a per-language preloaded cache); fixes the English apostrophes, which Compose resources don't unescape; replaces the S0 placeholder. `rateLimitedNoTime`'s English fallback now matches its resource.
- S1 `sinew-exception`: the 19 built-in types (no `CancelException` on Kotlin), `ExceptionHandler`, `envelopeDataMissing`, `processCall` (cancellation first, pass-through, first-match handlers, reporting of unexpected and parse failures), `processResult`, `CrashReporter` (`None`, `install`, `current`), `Localizer` and `displayMessage`; replaces the S0 placeholder.
- S1 `sinew-models`: `Domain`, `Remote`, `Entity`, `PlatformContext`; the error base contract (`SinewException` with `errorMessage`, `ExceptionLayer`, `ErrorMessage`, `EnvelopeDataMissing`); `Envelope` with the optional `failure()` and `message()` hooks, `EnvelopeFailure`, the four envelope bases, `PagingMetaEnvelope` and the `DomainNotParsed` stub serializer; `PagingDomain`, `PagingMetaDomain`, `PagingEntity`, `ViewState`, and Sinew's own `Result` (`Success(data, message)` / `Failure(error)`) in place of `kotlin.Result`.
- S0: project skeleton. One Kotlin Multiplatform module per Sinew package (Android, iOS, JVM, wasmJs), the `com.srctool.*` convention plugins, split version catalogs, the dependency-graph check, the BOM and the sample app.
