package com.srctool.sinew.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a backend's error body says: a message to show, and per-field validation messages.
 *
 * @property message the backend's text for the user, never blank.
 * @property fieldErrors validation messages by field name, never empty.
 */
public data class ErrorBody(
    val message: String?,
    val fieldErrors: Map<String, List<String>>?,
)

/**
 * Reads a backend's error body. Each client runs its own reader on every failed response, and the guard turns a
 * readable [ErrorBody.message] into a server message, and [ErrorBody.fieldErrors] into a `ValidationException`.
 *
 * A backend with another error shape supplies its own reader in `SinewConfig`.
 */
public fun interface ErrorBodyReader {
    /** The error body of a [status] response, or `null` when there's nothing readable. */
    public fun read(status: Int, body: String?): ErrorBody?

    public companion object {
        /**
         * Reads a JSON object's `message` (a string) and `errors` (`{ "field": ["message", …] }`, or a single string
         * per field). Returns `null` for an empty, HTML or non-JSON body, or an object with neither. A blank message,
         * an empty list or a non-string entry is ignored, so it never reaches the user.
         */
        public val Default: ErrorBodyReader = ErrorBodyReader { _, body -> readDefault(body) }
    }
}

private fun readDefault(body: String?): ErrorBody? {
    if (body.isNullOrBlank()) return null
    val root = try {
        Json.parseToJsonElement(body)
    } catch (_: SerializationException) {
        return null
    } as? JsonObject ?: return null
    val message = root["message"].string()?.takeIf { it.isNotBlank() }
    val fieldErrors = (root["errors"] as? JsonObject)
        ?.mapNotNull { (field, value) -> value.messages().takeIf { it.isNotEmpty() }?.let { field to it } }
        ?.toMap()
        ?.takeIf { it.isNotEmpty() }
    return if (message == null && fieldErrors == null) null else ErrorBody(message, fieldErrors)
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.messages(): List<String> = when (this) {
    is JsonArray -> mapNotNull { it.string()?.takeIf { text -> text.isNotBlank() } }
    else -> listOfNotNull(string()?.takeIf { it.isNotBlank() })
}
