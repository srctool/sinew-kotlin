package com.srctool.sinew.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import kotlin.concurrent.Volatile

/** [KeyValueStore] on a DataStore Preferences file. Keys match by name, whatever the type they were written with. */
internal class DataStoreKeyValueStore(private val file: Path) : KeyValueStore {
    @Volatile
    private var opened: DataStore<Preferences>? = null

    private suspend fun store(): DataStore<Preferences> = opened ?: OpenStores.get(file).also { opened = it }

    private suspend fun read(key: String): Any? = store().data.first().asMap().entries.firstOrNull { it.key.name == key }?.value

    override suspend fun getString(key: String): String? = read(key) as? String
    override suspend fun setString(key: String, value: String) = write { it[stringPreferencesKey(key)] = value }

    override suspend fun getInt(key: String): Int? = read(key) as? Int
    override suspend fun setInt(key: String, value: Int) = write { it[intPreferencesKey(key)] = value }

    override suspend fun getBool(key: String): Boolean? = read(key) as? Boolean
    override suspend fun setBool(key: String, value: Boolean) = write { it[booleanPreferencesKey(key)] = value }

    override suspend fun getDouble(key: String): Double? = read(key) as? Double
    override suspend fun setDouble(key: String, value: Double) = write { it[doublePreferencesKey(key)] = value }

    override suspend fun remove(key: String) = write { prefs ->
        prefs.asMap().keys.filter { it.name == key }.forEach { prefs.remove(it) }
    }

    override suspend fun clear() = write { it.clear() }

    private suspend fun write(change: (MutablePreferences) -> Unit) {
        store().edit(change)
    }
}

/**
 * One DataStore per file per process: DataStore refuses a second active instance on the same file, and two
 * `KeyValueStore`s with the same name must share their values.
 */
internal object OpenStores {
    private val lock = Mutex()
    private val stores = mutableMapOf<Path, DataStore<Preferences>>()

    suspend fun get(file: Path): DataStore<Preferences> = lock.withLock {
        stores.getOrPut(file) { PreferenceDataStoreFactory.createWithPath(produceFile = { file }) }
    }
}
