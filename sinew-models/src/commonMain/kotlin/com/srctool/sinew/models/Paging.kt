package com.srctool.sinew.models

/** A page of items: one shape for every backend, whatever its meta fields are called. */
public data class PagingDomain<out T>(
    val items: List<T> = emptyList(),
    val meta: PagingMetaDomain = PagingMetaDomain(),
)

/** Page information. [nextKey] is the cursor for the next page; `""` means none. */
public data class PagingMetaDomain(
    val page: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    val totalPage: Int = 0,
    val hasNextPage: Boolean = false,
    val hasPreviousPage: Boolean = false,
    val nextKey: String = "",
)

/** A stored page. Its items needn't be [Entity] subclasses, so [toDomain] takes the item mapping. */
public data class PagingEntity<out T>(
    val items: List<T> = emptyList(),
    val meta: PagingMetaEntity = PagingMetaEntity(),
) {
    public fun <R> toDomain(convert: (T) -> R): PagingDomain<R> = PagingDomain(items.map(convert), meta.toDomain())
}

/** Stored page information, mapped field for field to [PagingMetaDomain]. */
public data class PagingMetaEntity(
    val page: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    val totalPage: Int = 0,
    val hasNextPage: Boolean = false,
    val hasPreviousPage: Boolean = false,
    val nextKey: String = "",
) {
    public fun toDomain(): PagingMetaDomain = PagingMetaDomain(page, limit, total, totalPage, hasNextPage, hasPreviousPage, nextKey)
}
