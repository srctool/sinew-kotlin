package com.srctool.sinew.models

/**
 * A page of items: one shape for every backend, whatever its meta fields are called.
 *
 * @property items The items of this page.
 * @property meta The page information.
 */
public data class PagingDomain<out T>(
    val items: List<T> = emptyList(),
    val meta: PagingMetaDomain = PagingMetaDomain(),
)

/**
 * Page information. [nextKey] is the cursor for the next page; `""` means none.
 *
 * @property page The page number the backend reported.
 * @property limit The page size the backend reported.
 * @property total The total number of items the backend reported.
 * @property totalPage The total number of pages the backend reported.
 * @property hasNextPage Whether a next page exists.
 * @property hasPreviousPage Whether a previous page exists.
 * @property nextKey The cursor for the next page; `""` means none.
 */
public data class PagingMetaDomain(
    val page: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    val totalPage: Int = 0,
    val hasNextPage: Boolean = false,
    val hasPreviousPage: Boolean = false,
    val nextKey: String = "",
)

/**
 * A stored page. Its items needn't be [Entity] subclasses, so [toDomain] takes the item mapping.
 *
 * @property items The stored items.
 * @property meta The stored page information.
 */
public data class PagingEntity<out T>(
    val items: List<T> = emptyList(),
    val meta: PagingMetaEntity = PagingMetaEntity(),
) {
    /** Maps the page to the domain, converting every item with [convert]. */
    public fun <R> toDomain(convert: (T) -> R): PagingDomain<R> = PagingDomain(items.map(convert), meta.toDomain())
}

/**
 * Stored page information, mapped field for field to [PagingMetaDomain].
 *
 * @property page The page number the backend reported.
 * @property limit The page size the backend reported.
 * @property total The total number of items the backend reported.
 * @property totalPage The total number of pages the backend reported.
 * @property hasNextPage Whether a next page exists.
 * @property hasPreviousPage Whether a previous page exists.
 * @property nextKey The cursor for the next page; `""` means none.
 */
public data class PagingMetaEntity(
    val page: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    val totalPage: Int = 0,
    val hasNextPage: Boolean = false,
    val hasPreviousPage: Boolean = false,
    val nextKey: String = "",
) {
    /** Maps every field to [PagingMetaDomain]. */
    public fun toDomain(): PagingMetaDomain = PagingMetaDomain(page, limit, total, totalPage, hasNextPage, hasPreviousPage, nextKey)
}
