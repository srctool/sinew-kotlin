package com.srctool.sinew.exception

import com.srctool.sinew.models.SinewException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Where the guards report bugs (unexpected and parse failures). The one documented global in Sinew: installed once
 * at the app's composition root, [None] by default. A [SinewException] carries its own stack trace, and reports use
 * its code and English fallback, never a translated text.
 */
public fun interface CrashReporter {
    public fun recordNonFatal(error: SinewException)

    @OptIn(ExperimentalAtomicApi::class)
    public companion object {
        /** Records nothing. The default. */
        public val None: CrashReporter = CrashReporter { }

        private val installed = AtomicReference(None)

        /** What the guards call. Safe to read from any thread. */
        public val current: CrashReporter get() = installed.load()

        /** Installs [reporter], replacing any earlier one. Tests reset with `install(CrashReporter.None)`. */
        public fun install(reporter: CrashReporter) {
            installed.store(reporter)
        }
    }
}
