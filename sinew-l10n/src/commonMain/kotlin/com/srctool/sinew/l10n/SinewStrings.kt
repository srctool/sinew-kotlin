package com.srctool.sinew.l10n

import com.srctool.sinew.l10n.resources.Res
import com.srctool.sinew.l10n.resources.allPluralStringResources
import com.srctool.sinew.l10n.resources.allStringResources
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment

/**
 * Sinew's translations of the `sinew.` message keys: English, and Indonesian (`in` and `id`). A key maps to the
 * resource of the same name with underscores (`sinew.error.noInternet` → `sinew_error_noInternet`).
 *
 * For callers outside Compose; a page uses [rememberSinewLocalizer].
 */
public object SinewStrings {
    /** Every key Sinew translates. */
    public val keys: Set<String> = (Res.allStringResources.keys + Res.allPluralStringResources.keys)
        .map { it.replace('_', '.') }
        .toSet()

    /**
     * The text for [key] in [environment]'s language, with [args] filled in, or `null` when Sinew doesn't translate
     * the key. A plural key takes its quantity from its first numeric argument (`{ "seconds": 30 }`).
     */
    public suspend fun resolve(
        key: String,
        args: Map<String, Any> = emptyMap(),
        environment: ResourceEnvironment = getSystemResourceEnvironment(),
    ): String? {
        val name = key.replace('.', '_')
        val formatArgs = args.values.toTypedArray()
        Res.allStringResources[name]?.let { return getString(environment, it, *formatArgs) }
        Res.allPluralStringResources[name]?.let { plural ->
            val quantity = args.values.firstNotNullOfOrNull { (it as? Number)?.toInt() } ?: return null
            return getPluralString(environment, plural, quantity, *formatArgs)
        }
        return null
    }
}
