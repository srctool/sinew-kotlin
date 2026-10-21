package com.srctool.sinew.testing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
public actual fun fixture(name: String): String {
    val folder = getenv("SINEW_TEST_RESOURCES")?.toKString()
        ?: missingFixture(name, "SINEW_TEST_RESOURCES isn't set for the simulator test; see fixture's documentation")
    return NSString.stringWithContentsOfFile("$folder/$name", NSUTF8StringEncoding, null)
        ?: missingFixture(name, "not in $folder")
}
