package com.srctool.sinew.testing

public actual fun fixture(name: String): String {
    val path = "/base/kotlin/$name"
    val status = fetchStatus(path)
    if (status != 200) missingFixture(name, "GET $path answered $status; is tool/karma.config.d set up?")
    return fetchText(path)
}

// A synchronous request: fixture reads like a file, and the Karma server is local.
@OptIn(ExperimentalWasmJsInterop::class)
private fun fetchStatus(path: String): Int =
    js("{ const r = new XMLHttpRequest(); r.open('GET', path, false); r.send(); return r.status; }")

@OptIn(ExperimentalWasmJsInterop::class)
private fun fetchText(path: String): String =
    js("{ const r = new XMLHttpRequest(); r.open('GET', path, false); r.send(); return r.responseText; }")
