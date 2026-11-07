package com.srctool.sinew.sample

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaDomain
import com.srctool.sinew.models.Result
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals

/** Answers each page when the test says, so the test sees the states in between. */
private class ScriptedBackend : OrderBackend {
    val calls = mutableListOf<Int>()
    private var pending = CompletableDeferred<Result<PagingDomain<Order>>>()

    override suspend fun orders(page: Int): Result<PagingDomain<Order>> {
        calls += page
        return pending.await().also { pending = CompletableDeferred() }
    }

    fun answerPage(page: Int, hasNext: Boolean) {
        val rows = (1..PAGE_SIZE).map { Order((page - 1) * PAGE_SIZE + it, "Order #${(page - 1) * PAGE_SIZE + it}", "On the way") }
        pending.complete(Result.Success(PagingDomain(rows, PagingMetaDomain(page = page, hasNextPage = hasNext))))
    }

    fun fail() {
        pending.complete(Result.Failure(NoInternetConnectionException("SMP", ExceptionLayer.Repository, "ORD")))
    }

    companion object {
        const val PAGE_SIZE = 3
    }
}

@OptIn(ExperimentalTestApi::class)
class OrderListFlowTest {
    private fun ComposeUiTest.waitForCalls(backend: ScriptedBackend, expected: List<Int>) {
        waitUntil(timeoutMillis = TIMEOUT) { backend.calls == expected }
    }

    private fun ComposeUiTest.waitForText(text: String): SemanticsNodeInteraction {
        waitUntil(timeoutMillis = TIMEOUT) { onAllNodesWithTextExists(text) }
        return onNodeWithText(text, substring = true)
    }

    private fun ComposeUiTest.onAllNodesWithTextExists(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theFullCycle() = runComposeUiTest {
        val backend = ScriptedBackend()
        val viewModel = OrderListViewModel(backend)
        setContent { SampleTheme { OrderListScreen(viewModel) } }

        // First load: the skeleton shows, without rows.
        waitForCalls(backend, listOf(1))
        assertEquals(false, onAllNodesWithTextExists("Order #1"))

        // The first page. It's short, so the list asks for the next one right away.
        backend.answerPage(1, hasNext = true)
        waitForText("Order #1").assertIsDisplayed()
        waitForCalls(backend, listOf(1, 2))

        // The next page fails: the rows stay, with a retry row, and nothing loads until it's tapped.
        backend.fail()
        waitForText("Retry")
        assertEquals(listOf(1, 2), backend.calls)
        waitForText("Order #1")

        // Retry loads that page again.
        onNodeWithText("Retry").performClick()
        waitForCalls(backend, listOf(1, 2, 2))
        backend.answerPage(2, hasNext = false)
        waitForText("Order #6")

        // Pull to refresh goes back to the first page, with the skeleton.
        onNodeWithTag("orders").performTouchInput { swipeDown() }
        waitForCalls(backend, listOf(1, 2, 2, 1))
        waitUntil(timeoutMillis = TIMEOUT) { !onAllNodesWithTextExists("Order #1") }
        backend.answerPage(1, hasNext = true)
        waitForText("Order #1")
        waitForCalls(backend, listOf(1, 2, 2, 1, 2))
        backend.answerPage(2, hasNext = false)
        waitForText("Order #6")

        // The refresh button does the same, for a mouse.
        onNodeWithText("Refresh").performClick()
        waitForCalls(backend, listOf(1, 2, 2, 1, 2, 1))
        backend.answerPage(1, hasNext = false)
        waitForText("Order #1")

        // The end of the list: everything shows, and nothing more loads.
        onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(2)
        waitForIdle()
        assertEquals(listOf(1, 2, 2, 1, 2, 1), backend.calls)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
