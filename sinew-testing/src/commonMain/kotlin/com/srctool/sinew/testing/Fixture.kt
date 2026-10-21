package com.srctool.sinew.testing

/**
 * The text of the test resource [name] (`src/commonTest/resources/<name>`), so response shapes live as files beside
 * the tests: `fixture("orders_page.json")`. A missing fixture fails the test with its path, never returns "".
 *
 * | Target | Read from |
 * |---|---|
 * | JVM, Android host tests | the test classpath |
 * | iOS simulator | the folder in the `SINEW_TEST_RESOURCES` environment variable: the Mac's file system |
 * | wasm (browser) | `/base/kotlin/<name>`, served by the Karma test runner |
 *
 * Sinew's own build sets the last two up. An app sets them in its test configuration:
 *
 * ```kotlin
 * tasks.withType<KotlinNativeSimulatorTest>().configureEach {
 *     environment("SIMCTL_CHILD_SINEW_TEST_RESOURCES", file("src/commonTest/resources").path)
 * }
 * // wasmJs { browser { testTask { useKarma { useChromeHeadless(); useConfigDirectory(file("karma.config.d")) } } } },
 * // with karma.config.d/fixtures.js as in Sinew's tool/karma.config.d.
 * ```
 */
public expect fun fixture(name: String): String

internal fun missingFixture(name: String, where: String): Nothing =
    throw AssertionError("No fixture '$name': expected src/commonTest/resources/$name ($where)")
