package com.srctool.sinew.devtools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What devtools hide before anything is stored. Redaction runs **when recording**, so no screen, copy or export can
 * show a secret.
 *
 * A name is normalized before matching: lowercased, with `_`, `-` and `.` removed, so `refreshToken`,
 * `refresh_token` and `Refresh-Token` are all `refreshtoken`. A field, query parameter or header is sensitive when its
 * normalized name contains one of [fieldParts] or equals one of [exactFields]. A header is also sensitive when it's one
 * of [headers]. Matching runs at every depth of a JSON body.
 *
 * Apps extend [Default] with [plus] and can't remove a default, so a custom list never drops `Authorization`.
 */
public class Redaction private constructor(
    /** Header names, normalized, whose values are masked. */
    public val headers: Set<String>,
    /** Parts of a normalized name that make it sensitive. */
    public val fieldParts: Set<String>,
    /** Normalized names that are sensitive only as a whole: too short to match as a part. */
    public val exactFields: Set<String>,
    /** What a sensitive value is replaced with. */
    public val mask: String,
    /** A body is cut at this many bytes after redaction, keeping its real size. */
    public val maxBodyBytes: Int,
) {
    /** The additions [plus] accepts. Every set starts empty and is added to the defaults. */
    public class Extension internal constructor(
        /** Header names to redact as well. */
        public val headers: MutableSet<String> = mutableSetOf(),
        /** Name parts to redact as well. */
        public val fieldParts: MutableSet<String> = mutableSetOf(),
        /** Whole names to redact as well. */
        public val exactFields: MutableSet<String> = mutableSetOf(),
        /** A different body cap, in bytes, or `null` to keep it. */
        public var maxBodyBytes: Int? = null,
    )

    /** These rules plus the [extend] additions. Nothing is removed. */
    public operator fun plus(extend: Extension.() -> Unit): Redaction {
        val e = Extension().apply(extend)
        val cap = e.maxBodyBytes ?: maxBodyBytes
        require(cap > 0) { "maxBodyBytes must be positive, was $cap" }
        return Redaction(
            headers = headers + e.headers.map(::normalize),
            fieldParts = fieldParts + e.fieldParts.map(::normalize),
            exactFields = exactFields + e.exactFields.map(::normalize),
            mask = mask,
            maxBodyBytes = cap,
        )
    }

    /** These rules plus the header [names]: a client's `defaultHeaders`, which carry API keys and device ids. */
    public fun withHeaders(names: Collection<String>): Redaction = plus { headers += names }

    /** Whether a field or query parameter called [name] is masked. */
    public fun isSensitive(name: String): Boolean {
        val n = normalize(name)
        return n in exactFields || fieldParts.any { it in n }
    }

    /** Whether the header called [name] is masked. */
    public fun isSensitiveHeader(name: String): Boolean = normalize(name) in headers || isSensitive(name)

    /** [headers] with every sensitive value masked, keeping the names and how many values each has. */
    public fun redactHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.mapValues { (name, values) -> if (isSensitiveHeader(name)) values.map { mask } else values }

    /**
     * [url] with the value of every sensitive query parameter masked, and of every sensitive fragment parameter (an
     * OAuth redirect's `#access_token=…`). The rest is unchanged.
     */
    public fun redactUrl(url: String): String {
        val hash = url.indexOf('#')
        val base = if (hash < 0) url else url.substring(0, hash)
        val query = base.indexOf('?')
        val redacted = if (query < 0) base else base.substring(0, query + 1) + redactPairs(base.substring(query + 1))
        return if (hash < 0) redacted else redacted + "#" + redactPairs(url.substring(hash + 1))
    }

    /**
     * The body as it's stored. A JSON body (by its type, or text that starts like JSON) is walked and redacted, and
     * masked whole if it doesn't parse. A form body is redacted pair by pair. Other text is kept. Images, files and
     * anything that isn't UTF-8 keep only their type and size. Text is cut at [maxBodyBytes] after redaction.
     */
    public fun redactBody(bytes: ByteArray?, contentType: String?): RecordedBody {
        if (bytes == null || bytes.isEmpty()) return RecordedBody.None
        val size = bytes.size.toLong()
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val text = when {
            type == null -> bytes.decodeOrNull() ?: return RecordedBody.Binary(null, size)
            isText(type) -> bytes.decodeToString()
            else -> return RecordedBody.Binary(type, size)
        }
        val marks = Marks()
        val redacted = when {
            type == FORM -> redactPairs(text, marks)
            type?.isJson() == true || text.trimStart().let { it.startsWith('{') || it.startsWith('[') } -> redactJson(text, marks)
            else -> text
        }
        return cut(redacted, size, marks.masked)
    }

    /** Whether redacting has hidden anything yet: a body with a hidden secret can't be replayed. */
    private class Marks(var masked: Boolean = false)

    private fun redactJson(text: String, marks: Marks): String {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            marks.masked = true
            return mask                                     // can't be walked, so nothing in it can be kept
        }
        return walk(element, marks).toString()
    }

    private fun walk(element: JsonElement, marks: Marks): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (isSensitive(key)) JsonPrimitive(mask).also { marks.masked = true } else walk(value, marks)
            },
        )
        is JsonArray -> JsonArray(element.map { walk(it, marks) })
        is JsonPrimitive -> element
    }

    private fun redactPairs(pairs: String, marks: Marks = Marks()): String = pairs.split('&').joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0 || !isSensitive(decodeName(pair.substring(0, eq)))) {
            pair
        } else {
            marks.masked = true
            pair.substring(0, eq + 1) + mask
        }
    }

    private fun cut(text: String, size: Long, masked: Boolean): RecordedBody.Text {
        val encoded = text.encodeToByteArray()
        if (encoded.size <= maxBodyBytes) return RecordedBody.Text(text, size, truncated = false, masked = masked)
        var end = maxBodyBytes
        while (end > 0 && (encoded[end].toInt() and 0xC0) == 0x80) end--      // back to the start of a character
        return RecordedBody.Text(encoded.decodeToString(0, end), size, truncated = true, masked = masked)
    }

    public companion object {
        /** The default body cap: 64 KB. */
        public const val DEFAULT_MAX_BODY_BYTES: Int = 64 * 1024

        /** Sinew's rules. Extend them with [plus]. */
        public val Default: Redaction = Redaction(
            headers = setOf("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization").mapTo(mutableSetOf(), ::normalize),
            fieldParts = setOf("password", "passwd", "token", "secret", "otp", "pin", "apikey", "credential", "signature"),
            exactFields = setOf("code"),
            mask = "•••",
            maxBodyBytes = DEFAULT_MAX_BODY_BYTES,
        )

        /**
         * A stored secret as the Secure Store page shows it: `•••• 9f2c (412 chars)`. The last 4 characters tell two
         * tokens apart. A secret shorter than [SHOW_TAIL_FROM] characters (a PIN, a one-time code) shows only its
         * length, since 4 of its characters would give most of it away.
         */
        public fun maskSecret(value: String): String {
            val length = if (value.length == 1) "1 char" else "${value.length} chars"
            return if (value.length >= SHOW_TAIL_FROM) "•••• ${value.takeLast(4)} ($length)" else "•••• ($length)"
        }

        /** The shortest secret whose last 4 characters [maskSecret] shows. */
        public const val SHOW_TAIL_FROM: Int = 16

        private const val FORM = "application/x-www-form-urlencoded"

        private fun normalize(name: String): String = name.lowercase().filterNot { it == '_' || it == '-' || it == '.' }

        private fun String.isJson(): Boolean = this == "application/json" || endsWith("+json")

        private fun isText(type: String): Boolean =
            type.startsWith("text/") || type.isJson() || type == FORM || type.endsWith("+xml") ||
                type in setOf("application/xml", "application/javascript", "application/graphql", "application/x-ndjson")

        private fun ByteArray.decodeOrNull(): String? = try {
            decodeToString(throwOnInvalidSequence = true).takeUnless { '\u0000' in it }
        } catch (_: CharacterCodingException) {
            null
        }

        /** A query or form name, percent-decoded, so `new%5Fpassword` matches like `new_password`. */
        private fun decodeName(raw: String): String {
            val bytes = ArrayList<Byte>(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val hex = if (c == '%' && i + 2 <= raw.lastIndex) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
                when {
                    hex != null -> { bytes += hex.toByte(); i += 3 }
                    c == '+' -> { bytes += ' '.code.toByte(); i++ }
                    else -> { c.toString().encodeToByteArray().forEach { bytes += it }; i++ }
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

/** A request or response body as devtools store it, after [Redaction.redactBody]. */
public sealed interface RecordedBody {
    /** No body. */
    public data object None : RecordedBody

    /**
     * Redacted text, cut at the cap when [truncated]. [size] is the body's real size in bytes. [masked] says whether
     * anything in it was hidden, so a request whose body lost a secret is never replayed.
     */
    public data class Text(val text: String, val size: Long, val truncated: Boolean, val masked: Boolean = false) : RecordedBody

    /** A binary body: only its type and its size in bytes are kept. */
    public data class Binary(val contentType: String?, val size: Long) : RecordedBody
}
