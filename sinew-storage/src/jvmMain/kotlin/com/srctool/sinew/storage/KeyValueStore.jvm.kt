package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext
import okio.Path.Companion.toOkioPath
import java.nio.file.Path
import java.nio.file.Paths

internal actual fun platformKeyValueStore(platform: PlatformContext, name: String): KeyValueStore =
    KeyValueStore.create(appDataDirectory().resolve("sinew"), name)

/** A desktop store: the DataStore file `<name>.preferences_pb` in [directory]. */
public fun KeyValueStore.Companion.create(directory: Path, name: String = "sinew_prefs"): KeyValueStore {
    require(name.isNotBlank() && name.none { it == '/' || it == '\\' }) { "name must be a plain, non-blank file name" }
    return DataStoreKeyValueStore(directory.resolve("$name.preferences_pb").toOkioPath())
}

/** The OS's per-user app-data folder: Application Support on macOS, `%APPDATA%` on Windows, XDG elsewhere. */
internal fun appDataDirectory(): Path {
    val home = Paths.get(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> home.resolve("Library/Application Support")
        "win" in os -> System.getenv("APPDATA")?.let { Paths.get(it) } ?: home.resolve("AppData/Roaming")
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: home.resolve(".local/share")
    }
}
