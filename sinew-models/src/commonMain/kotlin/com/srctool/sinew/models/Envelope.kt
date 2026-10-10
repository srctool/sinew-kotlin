package com.srctool.sinew.models

/**
 * The shape of a response body, mapped to the domain. Sinew assumes no JSON keys: an app's subclass declares its
 * backend's fields, and fills the two optional hooks from its own keys when its backend has them.
 */
public interface Envelope<out R> {
    public fun toDomain(): R

    /** A failure reported inside a 2xx body (a `"success": false`). `null` means the call succeeded. */
    public fun failure(): EnvelopeFailure? = null

    /** The backend's text on success. `null` means none. */
    public fun message(): String? = null
}

/** A failure inside a 2xx body: the backend's message and, for a validation failure, its field messages. */
public data class EnvelopeFailure(
    val message: String? = null,
    val fieldErrors: Map<String, List<String>>? = null,
)

/** One object. [toDomain] throws [EnvelopeDataMissing] when [data] is absent. */
public abstract class ObjectEnvelope<out T : Remote<D>, D : Domain> : Envelope<D> {
    public abstract val data: T?
    override fun toDomain(): D = data?.toDomain() ?: throw EnvelopeDataMissing()
}

/** A list. An absent list maps to an empty one: a missing list is not an error. */
public abstract class ListEnvelope<out T : Remote<D>, D : Domain> : Envelope<List<D>> {
    public abstract val items: List<T>?
    override fun toDomain(): List<D> = items.orEmpty().map { it.toDomain() }
}

/**
 * A page. Every item is mapped through its own `toDomain()`, and so is [meta]. A subclass overrides [meta] with its
 * backend's own meta type; an absent meta maps to the default [PagingMetaDomain] (no next page).
 */
public abstract class PagedEnvelope<out T : Remote<D>, D : Domain> : Envelope<PagingDomain<D>> {
    public abstract val items: List<T>?
    public abstract val meta: PagingMetaEnvelope?
    override fun toDomain(): PagingDomain<D> =
        PagingDomain(items.orEmpty().map { it.toDomain() }, meta?.toDomain() ?: PagingMetaDomain())
}

/**
 * A backend's page information, shaped like its JSON: `total_page` on one backend, `max_page` on another, keys nested
 * or next to the items. Each app declares its own subclass and maps it to the one [PagingMetaDomain] shape.
 */
public abstract class PagingMetaEnvelope {
    public abstract fun toDomain(): PagingMetaDomain
}

/** A body with no payload. */
public abstract class VoidEnvelope : Envelope<Unit> {
    override fun toDomain() {}
}
