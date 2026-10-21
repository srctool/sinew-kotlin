package com.srctool.sinew.testing

import com.srctool.sinew.network.HttpLogger

/** An [HttpLogger] that keeps every line, so a test can assert what a client logged (and that no secret was). */
public class RecordingHttpLogger : HttpLogger {
    private val _lines = mutableListOf<String>()

    /** Every logged line, in order. */
    public val lines: List<String> get() = _lines.toList()

    override fun log(line: String) {
        _lines += line
    }
}
