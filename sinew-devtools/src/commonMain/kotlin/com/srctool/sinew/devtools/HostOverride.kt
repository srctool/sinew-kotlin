package com.srctool.sinew.devtools

import com.srctool.sinew.storage.KeyValueStore
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The API Host page's choice: which of the app's [hosts] the clients send to instead of their own base URL. The
 * choice is remembered across restarts in devtools' own namespace, and [select] with `null` resets it. Production
 * builds use the no-op artifact, so a host override never exists there.
 */
public class HostOverride {
    private val _hosts = MutableStateFlow<List<String>>(emptyList())
    private val _current = MutableStateFlow<String?>(null)
    private var store: KeyValueStore? = null

    /** The hosts the app lists, as base URLs (`https://staging.api.shop.com/v1`). */
    public val hosts: StateFlow<List<String>> = _hosts.asStateFlow()

    /** The chosen host, or `null` for each client's own. The ribbon shows it. */
    public val current: StateFlow<String?> = _current.asStateFlow()

    /**
     * Sets the app's [hosts] and restores the host chosen before the restart from [store], if it's still listed. A
     * stored host the app no longer lists is forgotten.
     */
    public suspend fun configure(hosts: List<String>, store: KeyValueStore? = null) {
        hosts.forEach { host ->
            val url = if (host.startsWith("http://") || host.startsWith("https://")) runCatching { Url(host) }.getOrNull() else null
            require(url != null && url.host.isNotEmpty() && ' ' !in host) {
                "a host must be an http or https base URL, was $host"
            }
        }
        this.store = store
        _hosts.value = hosts.distinct()
        val saved = store?.getString(KEY)
        _current.value = saved?.takeIf { it in hosts }
        if (saved != null && saved !in hosts) store.remove(KEY)
    }

    /** Sends every client to [host] from the next request, or back to its own with `null`, and remembers it. */
    public suspend fun select(host: String?) {
        require(host == null || host in _hosts.value) { "$host isn't one of the configured hosts" }
        _current.value = host
        store?.let { if (host == null) it.remove(KEY) else it.setString(KEY, host) }
    }

    internal companion object {
        /** Where the choice is kept, in devtools' own namespace. */
        const val KEY: String = "sinew.devtools.apiHost"
    }
}
