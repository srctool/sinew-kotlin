package com.srctool.sinew.testing

public actual fun fixture(name: String): String {
    val resource = Thread.currentThread().contextClassLoader?.getResource(name)
        ?: missingFixture(name, "not on the test classpath")
    return resource.readText()
}
