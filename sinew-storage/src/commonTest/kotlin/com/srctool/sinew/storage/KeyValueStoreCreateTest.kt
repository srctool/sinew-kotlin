package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import kotlin.test.Test
import kotlin.test.assertFailsWith

class KeyValueStoreCreateTest {
    @Test
    fun aNameThatIsntAPlainFileNameIsRefused() {
        if (!canCreateWithoutContext) return                         // Android needs a real Context
        for (name in listOf("", " ", "a/b", "a\\b")) {
            assertFailsWith<IllegalArgumentException>(name) { KeyValueStore.create(newPlatformContext(), name) }
        }
    }
}

/** Whether a test can build a `PlatformContext`: everywhere but the Android host. */
expect val canCreateWithoutContext: Boolean

expect fun newPlatformContext(): PlatformContext
