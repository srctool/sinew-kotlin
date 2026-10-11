package com.srctool.sinew.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.srctool.sinew.exception.Localizer
import kotlinx.coroutines.channels.Channel
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment

/**
 * The [Localizer] a page renders errors with: the app's own strings first, so an app can override any `sinew.` key,
 * then Sinew's English and Indonesian. It follows the app's current language.
 *
 * Reading resources is asynchronous, but rendering isn't, so Sinew's plain strings are preloaded for the current
 * language. A text not loaded yet (a plural with a new number) resolves to `null` for one frame, which shows the
 * English fallback, and the page recomposes once it's loaded.
 */
@Composable
public fun rememberSinewLocalizer(appStrings: Localizer? = null): Localizer {
    val environment = rememberResourceEnvironment()
    val localizer = remember(environment, appStrings) { CachingLocalizer(appStrings) }
    LaunchedEffect(localizer, environment) { localizer.load(environment) }
    return localizer
}

/**
 * Serves Sinew's texts synchronously from a cache that [load] fills: first every plain key, then each text a
 * [resolve] asked for. Cache writes are snapshot state, so a composition that read a missing text recomposes.
 */
internal class CachingLocalizer(private val appStrings: Localizer?) : Localizer {
    private val texts = mutableStateMapOf<String, String>()
    private val requested = mutableSetOf<String>()
    private val requests = Channel<Pair<String, Map<String, Any>>>(Channel.UNLIMITED)

    override fun resolve(key: String, args: Map<String, Any>): String? {
        appStrings?.resolve(key, args)?.let { return it }
        if (key !in SinewStrings.keys) return null
        val id = cacheId(key, args)
        texts[id]?.let { return it }
        if (requested.add(id)) requests.trySend(key to args)    // a channel send, not a state write, so it's safe during composition
        return null
    }

    /** Fills the cache for [environment] and keeps serving requests until cancelled. */
    suspend fun load(environment: ResourceEnvironment) {
        for (key in SinewStrings.keys) {
            SinewStrings.resolve(key, emptyMap(), environment)
                ?.takeIf { !it.contains('%') }                  // a plural or a formatted string needs its arguments
                ?.let { texts[cacheId(key, emptyMap())] = it }
        }
        for ((key, args) in requests) {
            SinewStrings.resolve(key, args, environment)?.let { texts[cacheId(key, args)] = it }
        }
    }

    private fun cacheId(key: String, args: Map<String, Any>) =
        if (args.isEmpty()) key else key + args.entries.sortedBy { it.key }.joinToString(",", "(", ")") { "${it.key}=${it.value}" }
}
