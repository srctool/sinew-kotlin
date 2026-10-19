package com.srctool.sinew.storage

/**
 * A store the devtools can browse: its name, its keys, and each value as text. `KeyValueStore` and `SecureStore`
 * implement it (the devtools mask secure values); an app's DAO can, to appear in the database browser. Nothing in
 * production calls it.
 */
public interface InspectableStore {
    /** The store's name, as the devtools list it. */
    public val name: String

    /** Every key, sorted. */
    public suspend fun keys(): List<String>

    /** [key]'s value as text, whatever its type, or `null` when absent. */
    public suspend fun read(key: String): String?
}
