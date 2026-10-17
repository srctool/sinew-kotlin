package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext

/**
 * Non-sensitive preferences and flags: onboarding seen, the theme, the last filter. Never tokens or personal data,
 * which go in `SecureStore` or the app's database with `FieldCipher`.
 *
 * Wrap it in typed stores with their own keys (`OrderPreferences.lastStatusFilter()`), so string keys stay in one
 * place. A value read as another type than it was written is absent, as is a missing one. Storage failures are
 * thrown as they come; `processStorageCall` turns them into typed exceptions.
 *
 * | Target | Backed by |
 * |---|---|
 * | Android | DataStore Preferences, `files/datastore/<name>.preferences_pb` |
 * | iOS | DataStore Preferences in Application Support, `sinew/<name>.preferences_pb` |
 * | JVM | DataStore Preferences in the user's app-data folder, `sinew/<name>.preferences_pb` |
 * | wasm | the page's `localStorage`, keys prefixed `<name>.` |
 */
public interface KeyValueStore {
    public suspend fun getString(key: String): String?
    public suspend fun setString(key: String, value: String)

    public suspend fun getInt(key: String): Int?
    public suspend fun setInt(key: String, value: Int)

    public suspend fun getBool(key: String): Boolean?
    public suspend fun setBool(key: String, value: Boolean)

    public suspend fun getDouble(key: String): Double?
    public suspend fun setDouble(key: String, value: Double)

    /** Removes [key], whatever its type. */
    public suspend fun remove(key: String)

    /** Removes every key of this store, and nothing of another store. */
    public suspend fun clear()

    public companion object {
        /**
         * The store named [name], one file (or `localStorage` prefix) per name. Stores with the same name share their
         * values. On the JVM, a desktop app should pass its own [name], or use the JVM's `create(directory, name)`.
         */
        public fun create(platform: PlatformContext, name: String = "sinew_prefs"): KeyValueStore {
            require(name.isNotBlank() && name.none { it == '/' || it == '\\' }) { "name must be a plain, non-blank file name" }
            return platformKeyValueStore(platform, name)
        }
    }
}

internal expect fun platformKeyValueStore(platform: PlatformContext, name: String): KeyValueStore
