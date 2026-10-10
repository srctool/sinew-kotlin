package com.srctool.sinew.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The two worked envelopes from the Models note, as an app would declare them. Test-only.

internal val testJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true                                 // an unknown enum value falls back to the property's default
}

internal enum class OrderStatus { Pending, Paid, Unknown }

@Serializable(with = Order.Unparsed::class)
internal data class Order(val id: String, val status: OrderStatus, val total: Long) : Domain {
    internal object Unparsed : DomainNotParsed<Order>("Order")
}

@Serializable
internal enum class OrderStatusRemote {
    @SerialName("pending") Pending,
    @SerialName("paid") Paid,
    Unknown,
}

@Serializable
internal data class OrderRemote(
    val id: String? = null,
    val status: OrderStatusRemote = OrderStatusRemote.Unknown,
    val total: Long? = null,
) : Remote<Order>() {
    override fun toDomain() = Order(
        id = id.orEmpty(),
        status = when (status) {
            OrderStatusRemote.Pending -> OrderStatus.Pending
            OrderStatusRemote.Paid -> OrderStatus.Paid
            OrderStatusRemote.Unknown -> OrderStatus.Unknown
        },
        total = total ?: 0,
    )
}

// Example 1: a backend with a success flag, a message and a `meta` object.

@Serializable
internal data class PageMeta(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPage: Int? = null,
    val hasNextPage: Boolean? = null,
) : PagingMetaEnvelope() {
    override fun toDomain() = PagingMetaDomain(
        page = page ?: 1, limit = limit ?: 0, total = total ?: 0, totalPage = totalPage ?: 0, hasNextPage = hasNextPage ?: false,
    )
}

@Serializable
internal data class PagedResponse<T : Remote<D>, D : Domain>(
    @SerialName("success") val ok: Boolean? = null,
    @SerialName("message") val text: String? = null,
    @SerialName("errors") val errors: Map<String, List<String>>? = null,
    @SerialName("data") override val items: List<T>? = null,
    @SerialName("meta") override val meta: PageMeta? = null,
) : PagedEnvelope<T, D>() {
    override fun failure() = if (ok == false) EnvelopeFailure(text, errors) else null
    override fun message() = text
}

@Serializable
internal data class ObjectResponse<T : Remote<D>, D : Domain>(
    @SerialName("success") val ok: Boolean? = null,
    @SerialName("message") val text: String? = null,
    @SerialName("errors") val errors: Map<String, List<String>>? = null,
    @SerialName("data") override val data: T? = null,
) : ObjectEnvelope<T, D>() {
    override fun failure() = if (ok == false) EnvelopeFailure(text, errors) else null
    override fun message() = text
}

@Serializable
internal data class ListResponse<T : Remote<D>, D : Domain>(
    @SerialName("data") override val items: List<T>? = null,
) : ListEnvelope<T, D>()

@Serializable
internal data class VoidResponse(@SerialName("message") val text: String? = null) : VoidEnvelope() {
    override fun message() = text
}

// Example 2: records nested in `data` with the page keys beside them, no success flag or message.
// The body is both the items' holder and the meta, so it extends PagingMetaEnvelope itself.

@Serializable
internal data class RecordsBody<T>(
    @SerialName("records") val records: List<T>? = null,
    @SerialName("max_page") val maxPage: Int? = null,
    @SerialName("total") val total: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
    @SerialName("current_page") val page: Int? = null,
) : PagingMetaEnvelope() {
    override fun toDomain() = PagingMetaDomain(
        page = page ?: 1, limit = pageSize ?: 0, total = total ?: 0, totalPage = maxPage ?: 0,
        hasNextPage = (page ?: 1) < (maxPage ?: 0),
    )
}

@Serializable
internal data class RecordsPagedResponse<T : Remote<D>, D : Domain>(
    @SerialName("data") val body: RecordsBody<T>? = null,
) : PagedEnvelope<T, D>() {
    override val items: List<T>? get() = body?.records
    override val meta: RecordsBody<T>? get() = body
}
