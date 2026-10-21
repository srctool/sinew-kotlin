package com.srctool.sinew.testing

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** On the JVM, `runTest` throws a failed test's error, so a whole test can be checked to fail. */
class ControlledFetchTeardownTest {
    @Test
    fun aCallLeftPendingFailsTheTestWhenItEnds() {
        val error = assertFailsWith<AssertionError> {
            runTest {
                val fetch = controlledFetch<String>()
                backgroundScope.launch { fetch(1, "") }
                runCurrent()
            }
        }
        assertTrue("Calls [0] were never answered" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aCallLeftPendingFailsWhateverOrderTeardownCancelsIn() {
        val error = assertFailsWith<AssertionError> {
            runTest {
                lateinit var fetch: ControlledFetch<String>
                // The call's coroutine is launched before the check, so teardown cancels it first.
                backgroundScope.launch { fetch(1, "") }
                fetch = controlledFetch()
                runCurrent()
            }
        }
        assertTrue("never answered" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aCallCancelledDuringTheTestIsNotPending() = runTest {
        val fetch = controlledFetch<String>()
        val superseded = launch { fetch(1, "") }
        runCurrent()
        superseded.cancel()
        runCurrent()
        assertTrue(fetch.calls.single().cancelled)
    }

    @Test
    fun answeredCallsPass() = runTest {
        val fetch = controlledFetch<String>()
        backgroundScope.launch { fetch(1, "") }
        runCurrent()
        fetch.succeed(0, listOf("a"))
    }
}
