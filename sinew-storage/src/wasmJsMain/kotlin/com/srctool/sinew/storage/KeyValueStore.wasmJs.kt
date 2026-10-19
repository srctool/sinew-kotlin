package com.srctool.sinew.storage

import com.srctool.sinew.models.PlatformContext

internal actual fun platformKeyValueStore(platform: PlatformContext, name: String): KeyValueStore = LocalStorageKeyValueStore(name)

/**
 * [KeyValueStore] on the page's `localStorage`: each key is `<name>.<key>`, each value a type tag and its text
 * (`s:`, `i:`, `b:`, `d:`), so a value read as another type is absent. `localStorage` belongs to the page's origin:
 * it's shared with every script on it, which is why only non-sensitive values go here.
 */
internal class LocalStorageKeyValueStore(override val name: String) : KeyValueStore {
    private val prefix = "$name."

    override suspend fun keys(): List<String> = storageKeys().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.sorted()

    override suspend fun read(key: String): String? =
        storageGet(prefix + key)?.takeIf { it.length >= 2 && it[0] in "sibd" && it[1] == ':' }?.substring(2)

    private fun tagged(key: String, tag: Char): String? =
        storageGet(prefix + key)?.takeIf { it.length >= 2 && it[0] == tag && it[1] == ':' }?.substring(2)

    private fun write(key: String, tag: Char, text: String) = storageSet(prefix + key, "$tag:$text")

    override suspend fun getString(key: String): String? = tagged(key, 's')
    override suspend fun setString(key: String, value: String) = write(key, 's', value)

    override suspend fun getInt(key: String): Int? = tagged(key, 'i')?.toIntOrNull()
    override suspend fun setInt(key: String, value: Int) = write(key, 'i', value.toString())

    override suspend fun getBool(key: String): Boolean? = tagged(key, 'b')?.toBooleanStrictOrNull()
    override suspend fun setBool(key: String, value: Boolean) = write(key, 'b', value.toString())

    override suspend fun getDouble(key: String): Double? = tagged(key, 'd')?.toDoubleOrNull()
    override suspend fun setDouble(key: String, value: Double) = write(key, 'd', value.toString())

    override suspend fun remove(key: String) = storageRemove(prefix + key)

    override suspend fun clear() {
        storageKeys().filter { it.startsWith(prefix) }.forEach(::storageRemove)
    }
}

private fun storageGet(key: String): String? = js("localStorage.getItem(key)")

private fun storageSet(key: String, value: String): Unit = js("localStorage.setItem(key, value)")

private fun storageRemove(key: String): Unit = js("localStorage.removeItem(key)")

private fun storageKeyCount(): Int = js("localStorage.length")

private fun storageKeyAt(index: Int): String? = js("localStorage.key(index)")

private fun storageKeys(): List<String> = (0 until storageKeyCount()).mapNotNull(::storageKeyAt)
