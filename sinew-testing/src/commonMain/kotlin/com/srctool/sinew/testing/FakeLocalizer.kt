package com.srctool.sinew.testing

import com.srctool.sinew.exception.Localizer

/**
 * A [Localizer] over [strings], filling `{name}` placeholders from the arguments. A key it doesn't have resolves to
 * the key itself, so a test asserts which key a page showed; with [echoKeys] off it resolves to `null`, as an app
 * without that translation does, and the English fallback shows. [requested] records every key asked for.
 */
public class FakeLocalizer(
    private val strings: Map<String, String> = emptyMap(),
    private val echoKeys: Boolean = true,
) : Localizer {
    private val _requested = mutableListOf<String>()

    /** Every key resolved, in order. */
    public val requested: List<String> get() = _requested.toList()

    override fun resolve(key: String, args: Map<String, Any>): String? {
        _requested += key
        val text = strings[key] ?: return if (echoKeys) key else null
        return args.entries.fold(text) { result, (name, value) -> result.replace("{$name}", value.toString()) }
    }
}
