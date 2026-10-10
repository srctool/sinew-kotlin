package com.srctool.sinew.models

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeTest {
    private val order1 = Order("o-1", OrderStatus.Pending, 1200)
    private val order2 = Order("o-2", OrderStatus.Paid, 800)
    private val twoOrders = """[ { "id": "o-1", "status": "pending", "total": 1200 }, { "id": "o-2", "status": "paid", "total": 800 } ]"""

    // Example 1: success flag, message, meta object

    @Test
    fun pagedEnvelopeMapsEveryItemAndItsMeta() {
        val json = """{ "success": true, "message": "", "data": $twoOrders,
            "meta": { "page": 1, "limit": 20, "total": 95, "totalPage": 5, "hasNextPage": true } }"""
        val page = testJson.decodeFromString<PagedResponse<OrderRemote, Order>>(json).toDomain()
        assertEquals(PagingDomain(listOf(order1, order2), PagingMetaDomain(page = 1, limit = 20, total = 95, totalPage = 5, hasNextPage = true)), page)
    }

    @Test
    fun pagedEnvelopeWithoutMetaHasTheDefaultMetaAndNoNextPage() {
        val page = testJson.decodeFromString<PagedResponse<OrderRemote, Order>>("""{ "data": $twoOrders }""").toDomain()
        assertEquals(PagingMetaDomain(), page.meta)
        assertEquals(2, page.items.size)
    }

    @Test
    fun metaWithMissingKeysFallsBackToTheAppMetaDefaults() {
        val page = testJson.decodeFromString<PagedResponse<OrderRemote, Order>>("""{ "data": [], "meta": { "total": 3 } }""").toDomain()
        assertEquals(PagingMetaDomain(page = 1, total = 3), page.meta)
    }

    @Test
    fun metaIsMappedThroughItsOwnToDomain() {
        val meta: PagingMetaEnvelope = PageMeta(page = 2, limit = 10, total = 35, totalPage = 4, hasNextPage = true)
        assertEquals(PagingMetaDomain(page = 2, limit = 10, total = 35, totalPage = 4, hasNextPage = true), meta.toDomain())
    }

    @Test
    fun objectEnvelopeMapsItsPayload() {
        val envelope = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>(
            """{ "success": true, "data": { "id": "o-1", "status": "pending", "total": 1200 } }""",
        )
        assertEquals(order1, envelope.toDomain())
    }

    @Test
    fun missingKeysDegradeOneValueNotTheWholePayload() {
        val envelope = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>("""{ "data": { "id": "o-1" } }""")
        assertEquals(Order("o-1", OrderStatus.Unknown, 0), envelope.toDomain())
    }

    @Test
    fun unknownEnumValueDecodesToUnknown() {
        val envelope = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>(
            """{ "data": { "id": "o-1", "status": "refunded", "total": 5 } }""",
        )
        assertEquals(OrderStatus.Unknown, envelope.toDomain().status)
    }

    @Test
    fun successFalseWithErrorsIsReportedByFailure() {
        val envelope = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>(
            """{ "success": false, "message": "Please check the form.", "errors": { "email": ["Email is taken."] }, "data": null }""",
        )
        assertEquals(EnvelopeFailure("Please check the form.", mapOf("email" to listOf("Email is taken."))), envelope.failure())
    }

    @Test
    fun successFalseWithoutErrorsIsReportedByFailure() {
        val envelope = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>("""{ "success": false, "message": "Out of stock." }""")
        assertEquals(EnvelopeFailure("Out of stock."), envelope.failure())
    }

    @Test
    fun successTrueAndAMissingFlagBothMeanSucceeded() {
        val withFlag = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>("""{ "success": true, "data": { "id": "o-1" } }""")
        val withoutFlag = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>("""{ "data": { "id": "o-1" } }""")
        assertNull(withFlag.failure())
        assertNull(withoutFlag.failure())
    }

    @Test
    fun messageIsReportedWhenPresentAndNullWhenAbsent() {
        val withMessage = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>("""{ "message": "Order placed.", "data": { "id": "o-1" } }""")
        val withoutMessage = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>("""{ "data": { "id": "o-1" } }""")
        assertEquals("Order placed.", withMessage.message())
        assertNull(withoutMessage.message())
    }

    @Test
    fun objectEnvelopeWithNullDataThrowsEnvelopeDataMissing() {
        val envelope = testJson.decodeFromString<ObjectResponse<OrderRemote, Order>>("""{ "success": true, "data": null }""")
        assertFailsWith<EnvelopeDataMissing> { envelope.toDomain() }
    }

    @Test
    fun listEnvelopeMapsEveryItemAndTreatsNullAsEmpty() {
        assertEquals(listOf(order1, order2), testJson.decodeFromString<ListResponse<OrderRemote, Order>>("""{ "data": $twoOrders }""").toDomain())
        assertEquals(emptyList(), testJson.decodeFromString<ListResponse<OrderRemote, Order>>("""{ "data": null }""").toDomain())
    }

    @Test
    fun pagedEnvelopeWithNullDataIsAnEmptyPage() {
        assertEquals(emptyList(), testJson.decodeFromString<PagedResponse<OrderRemote, Order>>("""{ "data": null }""").toDomain().items)
    }

    @Test
    fun voidEnvelopeMapsToUnitAndCanStillCarryAMessage() {
        val envelope = testJson.decodeFromString<VoidResponse>("""{ "message": "Deleted." }""")
        assertEquals(Unit, envelope.toDomain())
        assertEquals("Deleted.", envelope.message())
    }

    // Example 2: records nested in data, no success flag or message

    @Test
    fun recordsShapedBackendMapsItsItemsAndComputesHasNextPage() {
        val json = """{ "data": { "records": $twoOrders, "max_page": 5, "total": 95, "page_size": 20, "current_page": 1 } }"""
        val page = testJson.decodeFromString<RecordsPagedResponse<OrderRemote, Order>>(json).toDomain()
        assertEquals(PagingDomain(listOf(order1, order2), PagingMetaDomain(page = 1, limit = 20, total = 95, totalPage = 5, hasNextPage = true)), page)
    }

    @Test
    fun recordsShapedBackendOnTheLastPageHasNoNextPage() {
        val json = """{ "data": { "records": [], "max_page": 5, "current_page": 5 } }"""
        assertEquals(false, testJson.decodeFromString<RecordsPagedResponse<OrderRemote, Order>>(json).toDomain().meta.hasNextPage)
    }

    @Test
    fun backendWithoutFlagOrMessageKeepsBothHooksNull() {
        val envelope = testJson.decodeFromString<RecordsPagedResponse<OrderRemote, Order>>("""{ "data": { "records": [] } }""")
        assertNull(envelope.failure())
        assertNull(envelope.message())
    }

    // DomainNotParsed

    @Test
    fun decodingAGenericEnvelopeNeverCallsTheDomainStub() {
        // The stub throws when called, so decoding at all proves it wasn't.
        val envelope = testJson.decodeFromString<PagedResponse<OrderRemote, Order>>("""{ "data": $twoOrders }""")
        assertTrue(envelope.toDomain().items.isNotEmpty())
    }

    @Test
    fun theDomainStubThrowsWhenUsed() {
        assertFailsWith<IllegalStateException> { testJson.decodeFromString(Order.Unparsed, "{}") }
        assertFailsWith<IllegalStateException> { testJson.encodeToString(Order.Unparsed, order1) }
    }

    @Test
    fun theDomainStubHasADistinctSerialName() {
        assertEquals("com.srctool.sinew.DomainNotParsed.Order", Order.Unparsed.descriptor.serialName)
    }
}
