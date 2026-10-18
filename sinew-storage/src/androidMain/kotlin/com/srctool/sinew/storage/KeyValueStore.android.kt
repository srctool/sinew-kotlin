package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import okio.Path.Companion.toOkioPath

internal actual fun platformKeyValueStore(platform: PlatformContext, name: String): KeyValueStore =
    DataStoreKeyValueStore(platform.context.applicationContext.filesDir.resolve("datastore/$name.preferences_pb").toOkioPath())
