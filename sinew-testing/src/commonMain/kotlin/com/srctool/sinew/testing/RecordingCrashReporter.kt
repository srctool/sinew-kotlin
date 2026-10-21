package com.srctool.sinew.testing

import com.srctool.sinew.exception.CrashReporter
import com.srctool.sinew.models.SinewException

/** A [CrashReporter] that keeps every report, for assertions. */
public class RecordingCrashReporter : CrashReporter {
    private val _reports = mutableListOf<SinewException>()

    /** Every reported exception, in order. */
    public val reports: List<SinewException> get() = _reports.toList()

    /** The codes of [reports], in order: the usual assertion. */
    public val codes: List<String> get() = _reports.map { it.code }

    override fun recordNonFatal(error: SinewException) {
        _reports += error
    }

    /** Forgets every report. */
    public fun clear() {
        _reports.clear()
    }
}
