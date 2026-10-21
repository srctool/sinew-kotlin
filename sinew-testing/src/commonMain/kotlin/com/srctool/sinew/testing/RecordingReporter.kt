package com.srctool.sinew.testing

import com.srctool.sinew.exception.CrashReporter

/**
 * Installs a [RecordingCrashReporter] for [block] and puts the previous reporter back afterwards, even when [block]
 * fails. A block, not a JUnit rule: rules don't exist in common tests.
 *
 * ```kotlin
 * @Test fun aBugIsReported() = runTest {
 *     withRecordingReporter { reporter ->
 *         repository.load()
 *         assertEquals(listOf("ORD-R-LOAD-GEN"), reporter.codes)
 *     }
 * }
 * ```
 *
 * The reporter is global: tests that use it run one at a time, as `runTest` tests do.
 */
public inline fun <T> withRecordingReporter(block: (RecordingCrashReporter) -> T): T {
    val previous = CrashReporter.current
    val reporter = RecordingCrashReporter()
    CrashReporter.install(reporter)
    try {
        return block(reporter)
    } finally {
        CrashReporter.install(previous)
    }
}
