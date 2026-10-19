package com.srctool.sinew.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `pagedQuery` over a real in-memory SQLite table, with the `LIMIT … OFFSET …` query an app's DAO runs. */
class SqlitePagedQueryTest {
    private val db: SQLiteConnection = BundledSQLiteDriver().open(":memory:").apply {
        execSQL("CREATE TABLE orders (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        for (i in 1..25) execSQL("INSERT INTO orders (id, name) VALUES ($i, 'order-$i')")
    }

    @AfterTest
    fun close() = db.close()

    private fun page(offset: Int, count: Int): List<String> =
        db.prepare("SELECT name FROM orders ORDER BY id LIMIT ? OFFSET ?").use { statement ->
            statement.bindInt(1, count)
            statement.bindInt(2, offset)
            buildList { while (statement.step()) add(statement.getText(0)) }
        }

    @Test
    fun theFirstMiddleAndLastPages() = runTest {
        val first = pagedQuery(1, 10, fetch = ::page)
        assertEquals((1..10).map { "order-$it" }, first.items)
        assertTrue(first.meta.hasNextPage)
        assertEquals((11..20).map { "order-$it" }, pagedQuery(2, 10, fetch = ::page).items)
        val last = pagedQuery(3, 10, fetch = ::page)
        assertEquals((21..25).map { "order-$it" }, last.items)
        assertFalse(last.meta.hasNextPage)
    }

    @Test
    fun aZeroBasedFirstPageAndAnEmptyTable() = runTest {
        assertEquals((1..10).map { "order-$it" }, pagedQuery(0, 10, firstPage = 0, fetch = ::page).items)
        db.execSQL("DELETE FROM orders")
        val empty = pagedQuery(1, 10, fetch = ::page)
        assertTrue(empty.items.isEmpty())
        assertFalse(empty.meta.hasNextPage)
    }
}
