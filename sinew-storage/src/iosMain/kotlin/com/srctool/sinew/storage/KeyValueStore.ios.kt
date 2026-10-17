package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** In Application Support, not Documents: app data the user doesn't see in Files, and that iCloud backs up. */
internal actual fun platformKeyValueStore(platform: PlatformContext, name: String): KeyValueStore {
    val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    return DataStoreKeyValueStore("$support/sinew/$name.preferences_pb".toPath())
}
