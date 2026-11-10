package com.srctool.sinew.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The serializer a domain model declares so a generic envelope (`PagedResponse<OrderRemote, Order>`) can be decoded:
 * kotlinx.serialization needs a serializer for every type argument, though the domain type is never parsed.
 * It throws if it's ever used.
 *
 * ```
 * @Serializable(with = Order.Unparsed::class)
 * public data class Order(…) : Domain {
 *     internal object Unparsed : DomainNotParsed<Order>("Order")
 * }
 * ```
 */
public abstract class DomainNotParsed<D>(private val name: String) : KSerializer<D> {
    /** A descriptor that only names the type; it is never used to parse. */
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.srctool.sinew.DomainNotParsed.$name")

    /** Always throws: a domain model is never parsed. */
    override fun deserialize(decoder: Decoder): D = throw unparsed()

    /** Always throws: a domain model is never written. */
    override fun serialize(encoder: Encoder, value: D): Unit = throw unparsed()

    private fun unparsed() = IllegalStateException("$name is a domain model and is never (de)serialized; parse its Remote instead")
}
