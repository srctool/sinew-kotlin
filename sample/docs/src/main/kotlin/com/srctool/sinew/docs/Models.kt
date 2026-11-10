package com.srctool.sinew.docs

import com.srctool.sinew.models.Domain
import com.srctool.sinew.models.ObjectEnvelope
import com.srctool.sinew.models.Remote
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.fold
import kotlinx.serialization.Serializable

// region models
/** What the app needs, in business language. */
data class Order(val id: Int, val title: String) : Domain

/** What the backend sends, shaped like its JSON. */
@Serializable
class OrderRemote(val id: Int, val title: String) : Remote<Order>() {
    override fun toDomain() = Order(id, title)
}

/** A response body: one order under `data`. */
@Serializable
class OrderEnvelope(override val data: OrderRemote? = null) : ObjectEnvelope<OrderRemote, Order>()
// endregion

// region result
fun describe(result: Result<Order>): String = result.fold(
    onSuccess = { order -> "Loaded ${order.title}" },
    onFailure = { error -> "Failed: ${error.code}" },
)
// endregion
