package com.srctool.sinew.exception

import com.srctool.sinew.models.ErrorMessage
import com.srctool.sinew.models.SinewException

/** How the app resolves a local message key in the current language. `null` means not translated. */
public fun interface Localizer {
    public fun resolve(key: String, args: Map<String, Any>): String?
}

/**
 * The text a page shows, rendered every time it builds so a language switch updates an error already on screen.
 * Server text is shown unchanged. A local key goes through [localizer], falling back to the English fallback (never
 * the raw key). The code always ends the text. Validation field messages are shown beside their fields without one.
 */
public fun SinewException.displayMessage(localizer: Localizer?): String {
    val text = when (val m = errorMessage) {
        is ErrorMessage.Server -> m.text
        is ErrorMessage.Local -> localizer?.resolve(m.key, m.args) ?: message   // message is the fallback with its arguments filled in
    }
    return "$text ($code)"
}
