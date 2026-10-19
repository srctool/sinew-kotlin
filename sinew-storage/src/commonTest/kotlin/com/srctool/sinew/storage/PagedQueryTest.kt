package com.srctool.sinew.storage

import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaEntity
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.paging.LoadType
import com.srctool.sinew.paging.Pager
import com.srctool.sinew.paging.PagingState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A table of 25 rows, paged with a list-backed `LIMIT/OFFSET`, recording each call. */
private class Table(size: Int = 25) {
    val rows = List(size) { "order-$it" }
    val calls = mutableListOf<Pair<Int, Int>>()

    suspend fun page(page: Int, limit: Int, firstPage: Int = 1) = pagedQuery(page, limit, firstPage) { offset, count ->
        calls += offset to count
        rows.drop(offset).take(count)
    }
}

class PagedQueryTest {
    @Test
    fun theFirstPage() = runTest {
        val table = Table()
        val page = table.page(1, 10)
        assertEquals(table.rows.take(10), page.items)
        assertEquals(PagingMetaEntity(page = 1, limit = 10, hasNextPage = true, hasPreviousPage = false), page.meta)
        assertEquals(listOf(0 to 11), table.calls, "one extra row, no count query")
    }

    @Test
    fun aMiddlePage() = runTest {
        val table = Table()
        val page = table.page(2, 10)
        assertEquals(table.rows.subList(10, 20), page.items)
        assertTrue(page.meta.hasNextPage)
        assertTrue(page.meta.hasPreviousPage)
        assertEquals(listOf(10 to 11), table.calls)
    }

    @Test
    fun theLastPage() = runTest {
        val page = Table().page(3, 10)
        assertEquals(5, page.items.size)
        assertFalse(page.meta.hasNextPage)
    }

    @Test
    fun aLastPageThatIsExactlyFull() = runTest {
        val page = Table(size = 20).page(2, 10)
        assertEquals(10, page.items.size)
        assertFalse(page.meta.hasNextPage)
    }

    @Test
    fun anEmptyTable() = runTest {
        val page = Table(size = 0).page(1, 10)
        assertTrue(page.items.isEmpty())
        assertFalse(page.meta.hasNextPage)
    }

    @Test
    fun aPageBeyondTheLastIsEmpty() = runTest {
        val page = Table().page(9, 10)
        assertTrue(page.items.isEmpty())
        assertFalse(page.meta.hasNextPage)
    }

    @Test
    fun aZeroBasedFirstPage() = runTest {
        val table = Table()
        val first = table.page(0, 10, firstPage = 0)
        assertEquals(table.rows.take(10), first.items)
        assertFalse(first.meta.hasPreviousPage)
        assertEquals(table.rows.subList(10, 20), table.page(1, 10, firstPage = 0).items)
        assertEquals(listOf(0 to 11, 10 to 11), table.calls)
    }

    @Test
    fun totalsStayZero() = runTest {
        val meta = Table().page(1, 10).meta
        assertEquals(0, meta.total)
        assertEquals(0, meta.totalPage)
    }

    @Test
    fun aBadPageOrLimitIsRefused() = runTest {
        val table = Table()
        assertFailsWith<IllegalArgumentException> { table.page(1, 0) }
        assertFailsWith<IllegalArgumentException> { table.page(0, 10) }
        assertFailsWith<IllegalArgumentException> { table.page(Int.MAX_VALUE, 10) }
        assertTrue(table.calls.isEmpty())
    }

    @Test
    fun aLocalListPagesWithPagerOffsetLikeARemoteOne() = runTest {
        val table = Table()
        val pager = Pager.offset { page, _ ->
            processStorageCall("ORD", "GPO", StorageAccess.Read) { table.page(page, 10).toDomain { it } }
        }
        var state = PagingState<String>()
        pager.load(LoadType.Initial, state) { state = it }
        while (state.canLoadMore) pager.load(LoadType.More, state) { state = it }
        val done = assertIs<ViewState.Done<*>>(state.view)
        assertEquals(table.rows, (done.data as PagingDomain<*>).items)
        assertEquals(listOf(0 to 11, 10 to 11, 20 to 11), table.calls)
    }
}
